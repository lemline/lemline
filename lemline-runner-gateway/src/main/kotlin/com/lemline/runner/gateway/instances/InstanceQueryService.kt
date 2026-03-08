// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.instances

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.LemlineJson
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import com.lemline.runner.config.analyticsTypeResolved
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import com.lemline.runner.gateway.persistence.GatewayJdbcSupport
import com.lemline.runner.gateway.persistence.SqlQuery
import com.lemline.runner.gateway.persistence.getInstantOrNull
import com.lemline.runner.gateway.persistence.normalizePageSize
import com.lemline.runner.gateway.persistence.placeholders
import com.lemline.runner.gateway.persistence.setGatewayObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class InstanceQueryService(
    config: LemlineConfiguration,
) {

    @Inject
    lateinit var analyticsDataSourceProvider: AnalyticsDataSourceProvider

    private val analyticsType = AnalyticsType.fromConfigValue(config.analyticsTypeResolved)
    private val analyticsQualifiedTable =
        GatewayJdbcSupport.qualifiedAnalyticsTable(
            analyticsType = analyticsType,
            schema = config.analyticsSchemaResolved,
            table = ANALYTICS_LIFECYCLE_EVENTS_TABLE
        )
    private val payloadTextProjection = when (analyticsType) {
        AnalyticsType.POSTGRESQL -> "payload::text"
        AnalyticsType.H2 -> "payload"
    }

    suspend fun latestLifecycleSequence(): Long = withContext(Dispatchers.IO) {
        val sql = "SELECT COALESCE(MAX(id), 0) AS max_id FROM $analyticsQualifiedTable"
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("max_id") else 0L
                }
            }
        }
    }

    suspend fun listInstances(
        filter: InstanceListFilter,
        snapshotSequence: Long,
        page: InstancePageBoundary? = null,
    ): InstanceListResult = withContext(Dispatchers.IO) {
        if (filter.allowedNamespaces?.isEmpty() == true) {
            return@withContext InstanceListResult(emptyList(), null, 0L, snapshotSequence)
        }

        val normalizedPageSize = normalizePageSize(filter.pageSize)
        val query = buildInstanceListQuery(filter, snapshotSequence, page, normalizedPageSize + 1)
        val rows = executeInstanceLookupQuery(query)

        val hasMore = rows.size > normalizedPageSize
        val pageRows = if (hasMore) rows.take(normalizedPageSize) else rows
        val nextPage = pageRows.lastOrNull()
            ?.takeIf { hasMore }
            ?.let { InstancePageBoundary(it.instance.createdAt, it.instance.instanceId) }

        InstanceListResult(
            instances = pageRows.map { it.instance },
            nextPage = nextPage,
            totalCount = countInstances(filter, snapshotSequence),
            watchSequence = snapshotSequence,
        )
    }

    suspend fun findInstance(
        instanceId: UUID,
        snapshotSequence: Long,
    ): InstanceLookupRow? = withContext(Dispatchers.IO) {
        val query = buildInstanceLookupQuery(
            filter = InstanceListFilter(instanceId = instanceId, pageSize = 1),
            snapshotSequence = snapshotSequence,
        )
        executeInstanceLookupQuery(query).firstOrNull()
    }

    suspend fun listChangedInstanceSequencesAfter(
        afterSequenceExclusive: Long,
        limit: Int,
        filter: InstanceListFilter,
    ): List<WorkflowEventSequence> = withContext(Dispatchers.IO) {
        val normalizedLimit = limit.coerceIn(1, 1_000)
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()

        where += "id > ?"
        params += afterSequenceExclusive
        where += "lemline_workflow_id IS NOT NULL"
        where += "type IN ('${LifecycleEventType.WORKFLOW_CREATED.type}', '${LifecycleEventType.WORKFLOW_STARTED.type}', '${LifecycleEventType.WORKFLOW_COMPLETED.type}', '${LifecycleEventType.WORKFLOW_FAULTED.type}')"

        filter.namespace?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "lemline_workflow_namespace = ?"
            params += it
        }
        filter.name?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "lemline_workflow_name = ?"
            params += it
        }
        filter.version?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "lemline_workflow_version = ?"
            params += it
        }
        filter.instanceIdPrefix?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "${uuidToVarchar("lemline_workflow_id")} LIKE ?"
            params += "$it%"
        }
        filter.allowedNamespaces?.takeIf { it.isNotEmpty() }?.let { namespaces ->
            where += "lemline_workflow_namespace IN (${placeholders(namespaces.size)})"
            params.addAll(namespaces.sorted())
        }

        val sql = """
            SELECT id, lemline_workflow_id
            FROM $analyticsQualifiedTable
            WHERE ${where.joinToString(" AND ")}
            ORDER BY id ASC
            LIMIT ?
        """.trimIndent()

        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, value ->
                    stmt.setGatewayObject(index + 1, value)
                }
                stmt.setInt(params.size + 1, normalizedLimit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WorkflowEventSequence(
                                    sequence = rs.getLong("id"),
                                    workflowId = rs.getObject("lemline_workflow_id", UUID::class.java),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun matchesFilter(row: InstanceSummaryRow, filter: InstanceListFilter): Boolean {
        if (filter.namespace != null && row.namespace != filter.namespace) return false
        if (filter.name != null && row.name != filter.name) return false
        if (filter.version != null && row.version != filter.version) return false
        if (filter.status != null && row.status != filter.status) return false
        if (filter.instanceId != null && row.instanceId != filter.instanceId) return false
        if (filter.instanceIdPrefix != null && !row.instanceId.toString().startsWith(filter.instanceIdPrefix)) return false
        if (filter.createdFrom != null && row.createdAt < filter.createdFrom) return false
        if (filter.createdTo != null && row.createdAt >= filter.createdTo) return false
        if (filter.allowedNamespaces != null && row.namespace !in filter.allowedNamespaces) return false
        return true
    }

    private suspend fun executeInstanceLookupQuery(query: SqlQuery): List<InstanceLookupRow> = withContext(Dispatchers.IO) {
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(query.sql).use { stmt ->
                query.params.forEachIndexed { index, value ->
                    stmt.setGatewayObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val startedAt = rs.getInstantOrNull("started_at")
                            val completedAt = rs.getInstantOrNull("completed_at")
                            val faultedAt = rs.getInstantOrNull("faulted_at")
                            val summary = InstanceSummaryRow(
                                instanceId = rs.getObject("lemline_workflow_id", UUID::class.java),
                                namespace = rs.getString("lemline_workflow_namespace") ?: "",
                                name = rs.getString("lemline_workflow_name") ?: "",
                                version = rs.getString("lemline_workflow_version") ?: "",
                                status = InstanceStatus.fromRaw(rs.getString("status")),
                                createdAt = rs.getInstantOrNull("instance_created_at") ?: Instant.EPOCH,
                                startedAt = startedAt,
                                completedAt = completedAt,
                                faultedAt = faultedAt,
                                durationMs = calculateDurationMs(startedAt, completedAt, faultedAt),
                                error = parseErrorSummary(rs.getString("faulted_payload_json")),
                            )
                            add(
                                InstanceLookupRow(
                                    instance = summary,
                                    latestEventSequence = rs.getLong("latest_event_sequence").takeIf { !rs.wasNull() },
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun countInstances(filter: InstanceListFilter, snapshotSequence: Long): Long = withContext(Dispatchers.IO) {
        val query = buildInstanceCountQuery(filter, snapshotSequence)
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(query.sql).use { stmt ->
                query.params.forEachIndexed { index, value ->
                    stmt.setGatewayObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("total_count") else 0L
                }
            }
        }
    }

    private fun buildInstanceListQuery(
        filter: InstanceListFilter,
        snapshotSequence: Long,
        page: InstancePageBoundary?,
        limit: Int,
    ): SqlQuery {
        val queryParts = buildInstanceQueryParts(filter, snapshotSequence)
        val pagingWhere = if (page == null) {
            ""
        } else {
            """
            AND (
              instance_created_at < ?
              OR (instance_created_at = ? AND lemline_workflow_id < ?)
            )
            """.trimIndent()
        }
        val pagingParams = if (page == null) {
            emptyList()
        } else {
            listOf(page.createdAt, page.createdAt, page.instanceId)
        }

        val sql = """
            ${queryParts.cteSql}
            SELECT
              lemline_workflow_id,
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              instance_created_at,
              started_at,
              completed_at,
              faulted_at,
              status,
              faulted_payload_json,
              latest_event_sequence
            FROM instance_status
            WHERE ${queryParts.outerConditions.joinToString(" AND ")}
            $pagingWhere
            ORDER BY instance_created_at DESC, lemline_workflow_id DESC
            LIMIT ?
        """.trimIndent()

        return SqlQuery(sql, queryParts.params + pagingParams + limit)
    }

    private fun buildInstanceLookupQuery(
        filter: InstanceListFilter,
        snapshotSequence: Long,
    ): SqlQuery {
        val queryParts = buildInstanceQueryParts(filter, snapshotSequence)
        val sql = """
            ${queryParts.cteSql}
            SELECT
              lemline_workflow_id,
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              instance_created_at,
              started_at,
              completed_at,
              faulted_at,
              status,
              faulted_payload_json,
              latest_event_sequence
            FROM instance_status
            WHERE ${queryParts.outerConditions.joinToString(" AND ")}
            ORDER BY instance_created_at DESC, lemline_workflow_id DESC
            LIMIT 1
        """.trimIndent()
        return SqlQuery(sql, queryParts.params)
    }

    private fun buildInstanceCountQuery(filter: InstanceListFilter, snapshotSequence: Long): SqlQuery {
        val queryParts = buildInstanceQueryParts(filter, snapshotSequence)
        val sql = """
            ${queryParts.cteSql}
            SELECT COUNT(*) AS total_count
            FROM instance_status
            WHERE ${queryParts.outerConditions.joinToString(" AND ")}
        """.trimIndent()
        return SqlQuery(sql, queryParts.params)
    }

    private fun buildInstanceQueryParts(filter: InstanceListFilter, snapshotSequence: Long): InstanceQueryParts {
        val innerConditions = mutableListOf<String>()
        val innerParams = mutableListOf<Any>()
        val outerConditions = mutableListOf<String>()
        val outerParams = mutableListOf<Any>()

        innerConditions += "id <= ?"
        innerParams += snapshotSequence
        innerConditions += "lemline_workflow_id IS NOT NULL"

        filter.instanceId?.let {
            innerConditions += "lemline_workflow_id = ?"
            innerParams += it
        }
        filter.instanceIdPrefix?.trim()?.takeIf { it.isNotBlank() }?.let {
            innerConditions += "${uuidToVarchar("lemline_workflow_id")} LIKE ?"
            innerParams += "$it%"
        }

        outerConditions += "instance_created_at IS NOT NULL"

        filter.namespace?.trim()?.takeIf { it.isNotBlank() }?.let {
            outerConditions += "lemline_workflow_namespace = ?"
            outerParams += it
        }
        filter.name?.trim()?.takeIf { it.isNotBlank() }?.let {
            outerConditions += "lemline_workflow_name = ?"
            outerParams += it
        }
        filter.version?.trim()?.takeIf { it.isNotBlank() }?.let {
            outerConditions += "lemline_workflow_version = ?"
            outerParams += it
        }
        filter.allowedNamespaces?.takeIf { it.isNotEmpty() }?.let { namespaces ->
            outerConditions += "lemline_workflow_namespace IN (${placeholders(namespaces.size)})"
            outerParams.addAll(namespaces.sorted())
        }
        filter.status?.let {
            outerConditions += "status = ?"
            outerParams += it.name
        }
        filter.createdFrom?.let {
            outerConditions += "instance_created_at >= ?"
            outerParams += it
        }
        filter.createdTo?.let {
            outerConditions += "instance_created_at < ?"
            outerParams += it
        }

        val cteSql = """
            WITH instance_events AS (
              SELECT
                id,
                lemline_workflow_id,
                lemline_workflow_namespace,
                lemline_workflow_name,
                lemline_workflow_version,
                event_time,
                type,
                $payloadTextProjection AS payload_json
              FROM $analyticsQualifiedTable
              WHERE ${innerConditions.joinToString(" AND ")}
            ),
            instance_agg AS (
              SELECT
                lemline_workflow_id,
                MAX(lemline_workflow_namespace) AS lemline_workflow_namespace,
                MAX(lemline_workflow_name) AS lemline_workflow_name,
                MAX(lemline_workflow_version) AS lemline_workflow_version,
                MIN(CASE WHEN type = '${LifecycleEventType.WORKFLOW_CREATED.type}' THEN event_time END) AS created_at,
                MIN(CASE WHEN type = '${LifecycleEventType.WORKFLOW_STARTED.type}' THEN event_time END) AS started_at,
                MAX(CASE WHEN type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}' THEN event_time END) AS completed_at,
                MAX(CASE WHEN type = '${LifecycleEventType.WORKFLOW_FAULTED.type}' THEN event_time END) AS faulted_at,
                MAX(CASE WHEN type = '${LifecycleEventType.WORKFLOW_FAULTED.type}' THEN id END) AS latest_faulted_sequence,
                MAX(id) AS latest_event_sequence
              FROM instance_events
              GROUP BY lemline_workflow_id
            ),
            latest_faulted AS (
              SELECT
                event_row.lemline_workflow_id,
                event_row.payload_json AS faulted_payload_json
              FROM instance_events event_row
              JOIN instance_agg agg
                ON agg.latest_faulted_sequence = event_row.id
            ),
            instance_status AS (
              SELECT
                agg.lemline_workflow_id,
                agg.lemline_workflow_namespace,
                agg.lemline_workflow_name,
                agg.lemline_workflow_version,
                agg.created_at AS instance_created_at,
                agg.started_at,
                agg.completed_at,
                agg.faulted_at,
                agg.latest_event_sequence,
                faulted.faulted_payload_json,
                CASE
                  WHEN agg.completed_at IS NOT NULL AND agg.faulted_at IS NOT NULL THEN
                    CASE
                      WHEN agg.completed_at >= agg.faulted_at THEN 'COMPLETED'
                      ELSE 'FAULTED'
                    END
                  WHEN agg.completed_at IS NOT NULL THEN 'COMPLETED'
                  WHEN agg.faulted_at IS NOT NULL THEN 'FAULTED'
                  WHEN agg.started_at IS NOT NULL THEN 'RUNNING'
                  ELSE 'PENDING'
                END AS status
              FROM instance_agg agg
              LEFT JOIN latest_faulted faulted
                ON faulted.lemline_workflow_id = agg.lemline_workflow_id
            )
        """.trimIndent()

        return InstanceQueryParts(
            cteSql = cteSql,
            outerConditions = outerConditions,
            params = innerParams + outerParams,
        )
    }

    private fun calculateDurationMs(
        startedAt: Instant?,
        completedAt: Instant?,
        faultedAt: Instant?,
    ): Long? {
        val effectiveEnd = when {
            startedAt == null -> null
            completedAt != null && faultedAt != null -> maxOf(completedAt, faultedAt)
            completedAt != null -> completedAt
            faultedAt != null -> faultedAt
            else -> null
        } ?: return null

        return java.time.Duration.between(startedAt, effectiveEnd).toMillis()
    }

    private fun parseErrorSummary(faultedPayloadJson: String?): ErrorSummaryRow? {
        if (faultedPayloadJson.isNullOrBlank()) return null
        val root = runCatching { LemlineJson.jacksonMapper.readTree(faultedPayloadJson) }.getOrNull() ?: return null
        val error = root.path("data").path("error")
        if (error.isMissingNode || error.isNull) return null

        val type = error.path("type").takeIf(JsonNode::isTextual)?.asText()
        val status = error.path("status").takeIf(JsonNode::canConvertToInt)?.asInt()
        val title = error.path("title").takeIf(JsonNode::isTextual)?.asText()

        if (type.isNullOrBlank() && status == null && title.isNullOrBlank()) return null
        return ErrorSummaryRow(
            type = type ?: "about:blank",
            status = status,
            title = title ?: "",
        )
    }

    private fun uuidToVarchar(column: String): String = "CAST($column AS VARCHAR)"

    private data class InstanceQueryParts(
        val cteSql: String,
        val outerConditions: List<String>,
        val params: List<Any>,
    )
}
