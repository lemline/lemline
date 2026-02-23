// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import com.lemline.runner.config.analyticsTableResolved
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.nio.charset.StandardCharsets
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class InstanceQueryService(
    config: LemlineConfiguration,
) {

    @Inject
    @DataSource("analytics")
    lateinit var analyticsDataSource: Instance<AgroalDataSource>

    private val analyticsQualifiedTable =
        DashboardSqlSupport.qualifiedTable(config.analyticsSchemaResolved, config.analyticsTableResolved)

    suspend fun listInstances(filter: InstanceQueryFilter): ListInstancesResult = withContext(Dispatchers.IO) {
        val normalizedPageSize = normalizePageSize(filter.pageSize)
        val decodedCursor = filter.pageCursor?.let { decodeCursor(it) }

        val selectQuery = buildInstanceSelectQuery(
            filter = filter,
            cursorWorkflowId = decodedCursor?.workflowId,
            limit = normalizedPageSize + 1,
        )

        val allRows = requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(selectQuery.sql).use { stmt ->
                selectQuery.params.forEachIndexed { index, value ->
                    stmt.setObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WorkflowInstanceRow(
                                    workflowId = rs.getObject("lemline_workflow_id", UUID::class.java),
                                    namespace = rs.getString("lemline_workflow_namespace") ?: "",
                                    name = rs.getString("lemline_workflow_name") ?: "",
                                    version = rs.getString("lemline_workflow_version") ?: "",
                                    status = parseStatus(rs.getString("status")),
                                    createdAt = rs.getInstantOrNull("created_at"),
                                    startedAt = rs.getInstantOrNull("started_at"),
                                    completedAt = rs.getInstantOrNull("completed_at"),
                                    faultedAt = rs.getInstantOrNull("faulted_at"),
                                    durationMs = rs.getObject("duration_ms") as? Long,
                                    error = parseErrorSummary(
                                        type = rs.getString("error_type"),
                                        status = rs.getObject("error_status") as? Int,
                                        title = rs.getString("error_title"),
                                    ),
                                )
                            )
                        }
                    }
                }
            }
        }

        val hasMore = allRows.size > normalizedPageSize
        val pageRows = if (hasMore) allRows.take(normalizedPageSize) else allRows

        val totalCount = decodedCursor?.totalCount ?: if (decodedCursor == null) {
            countInstances(filter)
        } else {
            -1L
        }

        val nextCursor = if (hasMore && pageRows.isNotEmpty()) {
            encodeCursor(pageRows.last().workflowId, totalCount)
        } else {
            null
        }

        ListInstancesResult(
            instances = pageRows,
            nextCursor = nextCursor,
            totalCount = totalCount,
        )
    }

    suspend fun findInstance(workflowId: UUID): WorkflowInstanceRow? {
        val result = listInstances(
            InstanceQueryFilter(
                workflowId = workflowId,
                pageSize = 1,
            )
        )
        return result.instances.firstOrNull()
    }

    suspend fun latestSequence(): Long = withContext(Dispatchers.IO) {
        val sql = "SELECT COALESCE(MAX(id), 0) AS max_id FROM $analyticsQualifiedTable"
        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("max_id") else 0L
                }
            }
        }
    }

    suspend fun listWorkflowEventsAfter(
        afterSequenceExclusive: Long,
        limit: Int,
        filter: WatchInstancesFilter,
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

        val sql = """
            SELECT id, lemline_workflow_id, type
            FROM $analyticsQualifiedTable
            WHERE ${where.joinToString(" AND ")}
            ORDER BY id ASC
            LIMIT ?
        """.trimIndent()

        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, value ->
                    stmt.setObject(index + 1, value)
                }
                stmt.setInt(params.size + 1, normalizedLimit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WorkflowEventSequence(
                                    sequence = rs.getLong("id"),
                                    workflowId = rs.getObject("lemline_workflow_id", UUID::class.java),
                                    eventType = rs.getString("type"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun isFirstWorkflowEvent(workflowId: UUID, sequence: Long): Boolean = withContext(Dispatchers.IO) {
        val sql = """
            SELECT 1
            FROM $analyticsQualifiedTable
            WHERE lemline_workflow_id = ?
              AND id < ?
              AND type IN (
                '${LifecycleEventType.WORKFLOW_CREATED.type}',
                '${LifecycleEventType.WORKFLOW_STARTED.type}',
                '${LifecycleEventType.WORKFLOW_COMPLETED.type}',
                '${LifecycleEventType.WORKFLOW_FAULTED.type}'
              )
            LIMIT 1
        """.trimIndent()

        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setLong(2, sequence)
                stmt.executeQuery().use { rs ->
                    !rs.next()
                }
            }
        }
    }

    private suspend fun countInstances(filter: InstanceQueryFilter): Long = withContext(Dispatchers.IO) {
        val countQuery = buildInstanceCountQuery(filter)
        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(countQuery.sql).use { stmt ->
                countQuery.params.forEachIndexed { index, value ->
                    stmt.setObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("total_count") else 0L
                }
            }
        }
    }

    private fun buildInstanceSelectQuery(
        filter: InstanceQueryFilter,
        cursorWorkflowId: UUID?,
        limit: Int,
    ): SqlQuery {
        val queryParts = buildInstanceQueryParts(filter, includeCursor = true, cursorWorkflowId = cursorWorkflowId)

        val sql = """
            ${queryParts.cteSql}
            SELECT
              lemline_workflow_id,
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              created_at,
              started_at,
              completed_at,
              faulted_at,
              status,
              duration_ms,
              faulted_payload->'data'->'error'->>'type' AS error_type,
              (faulted_payload->'data'->'error'->>'status')::int AS error_status,
              faulted_payload->'data'->'error'->>'title' AS error_title
            FROM instance_status
            WHERE ${queryParts.outerConditions.joinToString(" AND ")}
            ORDER BY lemline_workflow_id DESC
            LIMIT ?
        """.trimIndent()

        return SqlQuery(sql = sql, params = queryParts.params + limit)
    }

    private fun buildInstanceCountQuery(filter: InstanceQueryFilter): SqlQuery {
        val queryParts = buildInstanceQueryParts(filter, includeCursor = false, cursorWorkflowId = null)

        val sql = """
            ${queryParts.cteSql}
            SELECT COUNT(*) AS total_count
            FROM instance_status
            WHERE ${queryParts.outerConditions.joinToString(" AND ")}
        """.trimIndent()

        return SqlQuery(sql = sql, params = queryParts.params)
    }

    private fun buildInstanceQueryParts(
        filter: InstanceQueryFilter,
        includeCursor: Boolean,
        cursorWorkflowId: UUID?,
    ): InstanceQueryParts {
        val innerConditions = mutableListOf<String>()
        val outerConditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        innerConditions += "lemline_workflow_id IS NOT NULL"
        innerConditions += "type IN ('${LifecycleEventType.WORKFLOW_CREATED.type}', '${LifecycleEventType.WORKFLOW_STARTED.type}', '${LifecycleEventType.WORKFLOW_COMPLETED.type}', '${LifecycleEventType.WORKFLOW_FAULTED.type}')"

        filter.namespace?.trim()?.takeIf { it.isNotBlank() }?.let {
            innerConditions += "lemline_workflow_namespace = ?"
            params += it
        }

        filter.workflowId?.let {
            innerConditions += "lemline_workflow_id = ?"
            params += it
        }

        filter.workflowIdPrefix?.trim()?.takeIf { it.isNotBlank() }?.let {
            innerConditions += "lemline_workflow_id::text LIKE ?"
            params += "$it%"
        }

        outerConditions += "1 = 1"

        filter.name?.trim()?.takeIf { it.isNotBlank() }?.let {
            outerConditions += "lemline_workflow_name = ?"
            params += it
        }

        filter.version?.trim()?.takeIf { it.isNotBlank() }?.let {
            outerConditions += "lemline_workflow_version = ?"
            params += it
        }

        filter.status?.let {
            outerConditions += "status = ?"
            params += it.name
        }

        filter.timeFrom?.let {
            outerConditions += "COALESCE(started_at, created_at) >= ?"
            params += it
        }

        filter.timeTo?.let {
            outerConditions += "COALESCE(started_at, created_at) <= ?"
            params += it
        }

        if (includeCursor && cursorWorkflowId != null) {
            outerConditions += "lemline_workflow_id < ?"
            params += cursorWorkflowId
        }

        val cteSql = """
            WITH instance_agg AS (
              SELECT
                lemline_workflow_id,
                MAX(lemline_workflow_namespace) AS lemline_workflow_namespace,
                MAX(lemline_workflow_name) AS lemline_workflow_name,
                MAX(lemline_workflow_version) AS lemline_workflow_version,
                MIN(event_time) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_CREATED.type}') AS created_at,
                MIN(event_time) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_STARTED.type}') AS started_at,
                MAX(event_time) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}') AS completed_at,
                MAX(event_time) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_FAULTED.type}') AS faulted_at,
                (array_agg(payload ORDER BY event_time DESC, id DESC) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_FAULTED.type}'))[1] AS faulted_payload
              FROM $analyticsQualifiedTable
              WHERE ${innerConditions.joinToString(" AND ")}
              GROUP BY lemline_workflow_id
            ),
            instance_status AS (
              SELECT
                lemline_workflow_id,
                lemline_workflow_namespace,
                lemline_workflow_name,
                lemline_workflow_version,
                created_at,
                started_at,
                completed_at,
                faulted_at,
                faulted_payload,
                CASE
                  WHEN completed_at IS NOT NULL AND faulted_at IS NOT NULL THEN
                    CASE
                      WHEN completed_at >= faulted_at THEN 'COMPLETED'
                      ELSE 'FAULTED'
                    END
                  WHEN completed_at IS NOT NULL THEN 'COMPLETED'
                  WHEN faulted_at IS NOT NULL THEN 'FAULTED'
                  WHEN started_at IS NOT NULL THEN 'RUNNING'
                  WHEN created_at IS NOT NULL THEN 'PENDING'
                  ELSE 'UNKNOWN'
                END AS status,
                CASE
                  WHEN started_at IS NULL THEN NULL
                  WHEN completed_at IS NOT NULL AND (faulted_at IS NULL OR completed_at >= faulted_at) THEN
                    CAST(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000 AS BIGINT)
                  WHEN faulted_at IS NOT NULL THEN
                    CAST(EXTRACT(EPOCH FROM (faulted_at - started_at)) * 1000 AS BIGINT)
                  ELSE NULL
                END AS duration_ms
              FROM instance_agg
            )
        """.trimIndent()

        return InstanceQueryParts(
            cteSql = cteSql,
            outerConditions = outerConditions,
            params = params,
        )
    }

    private fun parseStatus(raw: String?): WorkflowRuntimeStatus = when (raw) {
        "PENDING" -> WorkflowRuntimeStatus.PENDING
        "RUNNING" -> WorkflowRuntimeStatus.RUNNING
        "COMPLETED" -> WorkflowRuntimeStatus.COMPLETED
        "FAULTED" -> WorkflowRuntimeStatus.FAULTED
        else -> WorkflowRuntimeStatus.UNKNOWN
    }

    private fun parseErrorSummary(type: String?, status: Int?, title: String?): ErrorSummaryRow? {
        if (type.isNullOrBlank() && status == null && title.isNullOrBlank()) return null
        return ErrorSummaryRow(
            type = type ?: "about:blank",
            status = status ?: 0,
            title = title ?: "",
        )
    }

    private fun normalizePageSize(rawPageSize: Int): Int = rawPageSize.takeIf { it > 0 }?.coerceAtMost(200) ?: 50

    private fun decodeCursor(raw: String): DecodedCursor {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "page_cursor must not be blank" }

        runCatching { UUID.fromString(trimmed) }
            .onSuccess { return DecodedCursor(it, null) }

        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(trimmed), StandardCharsets.UTF_8)
        }.getOrElse {
            throw IllegalArgumentException("Invalid page_cursor: '$raw'")
        }

        val parts = decoded.split('|', limit = 2)
        require(parts.size == 2) { "Invalid page_cursor payload" }

        val workflowId = runCatching { UUID.fromString(parts[0]) }
            .getOrElse { throw IllegalArgumentException("Invalid page_cursor workflow_id") }

        val totalCount = parts[1].toLongOrNull()?.takeIf { it >= 0 }
        return DecodedCursor(workflowId, totalCount)
    }

    private fun encodeCursor(workflowId: UUID, totalCount: Long): String {
        val safeCount = if (totalCount >= 0) totalCount else -1L
        val payload = "$workflowId|$safeCount"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun requireAnalyticsDataSource(): AgroalDataSource {
        if (analyticsDataSource.isResolvable) return analyticsDataSource.get()
        throw IllegalStateException(
            "Analytics datasource 'analytics' is not available. Configure $LEMLINE_ANALYTICS_POSTGRES.*"
        )
    }

    private data class SqlQuery(
        val sql: String,
        val params: List<Any>,
    )

    private data class InstanceQueryParts(
        val cteSql: String,
        val outerConditions: List<String>,
        val params: List<Any>,
    )

    private data class DecodedCursor(
        val workflowId: UUID,
        val totalCount: Long?,
    )
}
