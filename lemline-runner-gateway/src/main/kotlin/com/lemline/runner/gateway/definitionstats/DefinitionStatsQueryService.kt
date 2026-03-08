// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.definitionstats

import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import com.lemline.runner.config.analyticsTypeResolved
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import com.lemline.runner.gateway.persistence.GatewayJdbcSupport
import com.lemline.runner.gateway.persistence.SqlQuery
import com.lemline.runner.gateway.persistence.normalizePageSize
import com.lemline.runner.gateway.persistence.placeholders
import com.lemline.runner.gateway.persistence.setGatewayObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class DefinitionStatsQueryService(
    config: LemlineConfiguration,
) {

    @Inject
    lateinit var analyticsDataSourceProvider: AnalyticsDataSourceProvider

    private val analyticsQualifiedTable =
        GatewayJdbcSupport.qualifiedAnalyticsTable(
            analyticsType = AnalyticsType.fromConfigValue(config.analyticsTypeResolved),
            schema = config.analyticsSchemaResolved,
            table = ANALYTICS_LIFECYCLE_EVENTS_TABLE
        )

    suspend fun listDefinitionStats(
        filter: DefinitionStatsFilter,
        snapshotSequence: Long,
        page: DefinitionStatsPageBoundary? = null,
    ): DefinitionStatsListResult = withContext(Dispatchers.IO) {
        if (filter.allowedNamespaces?.isEmpty() == true) {
            return@withContext DefinitionStatsListResult(emptyList(), null, 0L, snapshotSequence)
        }

        val normalizedPageSize = normalizePageSize(filter.pageSize)
        val query = buildStatsListQuery(filter, snapshotSequence, page, normalizedPageSize + 1)
        val rows = executeStatsQuery(query)

        val hasMore = rows.size > normalizedPageSize
        val pageRows = if (hasMore) rows.take(normalizedPageSize) else rows
        val nextPage = pageRows.lastOrNull()
            ?.takeIf { hasMore }
            ?.let { DefinitionStatsPageBoundary(it.namespace, it.name, it.version) }

        DefinitionStatsListResult(
            stats = pageRows,
            nextPage = nextPage,
            totalCount = countStats(filter, snapshotSequence),
            watchSequence = snapshotSequence,
        )
    }

    suspend fun queryDefinitionStatsSnapshot(
        filter: DefinitionStatsFilter,
        snapshotSequence: Long,
    ): List<DefinitionStatsRow> = withContext(Dispatchers.IO) {
        if (filter.allowedNamespaces?.isEmpty() == true) {
            return@withContext emptyList()
        }
        executeStatsQuery(buildStatsSnapshotQuery(filter, snapshotSequence))
    }

    private suspend fun executeStatsQuery(query: SqlQuery): List<DefinitionStatsRow> = withContext(Dispatchers.IO) {
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(query.sql).use { stmt ->
                query.params.forEachIndexed { index, value ->
                    stmt.setGatewayObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DefinitionStatsRow(
                                    namespace = rs.getString("lemline_workflow_namespace") ?: "",
                                    name = rs.getString("lemline_workflow_name") ?: "",
                                    version = rs.getString("lemline_workflow_version") ?: "",
                                    startedCount = rs.getLong("started_count"),
                                    completedCount = rs.getLong("completed_count"),
                                    faultedCount = rs.getLong("faulted_count"),
                                    runningCountAtTimeTo = rs.getLong("running_count_at_time_to"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun countStats(filter: DefinitionStatsFilter, snapshotSequence: Long): Long = withContext(Dispatchers.IO) {
        val query = buildStatsCountQuery(filter, snapshotSequence)
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

    private fun buildStatsListQuery(
        filter: DefinitionStatsFilter,
        snapshotSequence: Long,
        page: DefinitionStatsPageBoundary?,
        limit: Int,
    ): SqlQuery {
        val queryParts = buildStatsQueryParts(filter, snapshotSequence)
        val pagingWhere = if (page == null) {
            ""
        } else {
            """
            WHERE (
              lemline_workflow_namespace > ?
              OR (lemline_workflow_namespace = ? AND lemline_workflow_name > ?)
              OR (lemline_workflow_namespace = ? AND lemline_workflow_name = ? AND lemline_workflow_version < ?)
            )
            """.trimIndent()
        }
        val pagingParams = if (page == null) {
            emptyList()
        } else {
            listOf(page.namespace, page.namespace, page.name, page.namespace, page.name, page.version)
        }

        val sql = """
            ${queryParts.sql}
            SELECT
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              started_count,
              completed_count,
              faulted_count,
              running_count_at_time_to
            FROM grouped
            $pagingWhere
            ORDER BY lemline_workflow_namespace ASC, lemline_workflow_name ASC, lemline_workflow_version DESC
            LIMIT ?
        """.trimIndent()

        return SqlQuery(sql, queryParts.params + pagingParams + limit)
    }

    private fun buildStatsSnapshotQuery(filter: DefinitionStatsFilter, snapshotSequence: Long): SqlQuery {
        val queryParts = buildStatsQueryParts(filter, snapshotSequence)
        val sql = """
            ${queryParts.sql}
            SELECT
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              started_count,
              completed_count,
              faulted_count,
              running_count_at_time_to
            FROM grouped
            ORDER BY lemline_workflow_namespace ASC, lemline_workflow_name ASC, lemline_workflow_version DESC
        """.trimIndent()
        return SqlQuery(sql, queryParts.params)
    }

    private fun buildStatsCountQuery(filter: DefinitionStatsFilter, snapshotSequence: Long): SqlQuery {
        val queryParts = buildStatsQueryParts(filter, snapshotSequence)
        val sql = """
            ${queryParts.sql}
            SELECT COUNT(*) AS total_count
            FROM grouped
        """.trimIndent()
        return SqlQuery(sql, queryParts.params)
    }

    private fun buildStatsQueryParts(filter: DefinitionStatsFilter, snapshotSequence: Long): SqlQuery {
        val where = mutableListOf<String>()
        val selectParams = mutableListOf<Any>()
        val whereParams = mutableListOf<Any>()
        val groupedParams = mutableListOf<Any>()

        where += "id <= ?"
        whereParams += snapshotSequence
        where += "lemline_workflow_id IS NOT NULL"
        where += "type IN ('${LifecycleEventType.WORKFLOW_STARTED.type}', '${LifecycleEventType.WORKFLOW_COMPLETED.type}', '${LifecycleEventType.WORKFLOW_FAULTED.type}')"

        filter.namespace?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "lemline_workflow_namespace = ?"
            whereParams += it
        }
        filter.name?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "lemline_workflow_name = ?"
            whereParams += it
        }
        filter.version?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "lemline_workflow_version = ?"
            whereParams += it
        }
        filter.allowedNamespaces?.takeIf { it.isNotEmpty() }?.let { namespaces ->
            where += "lemline_workflow_namespace IN (${placeholders(namespaces.size)})"
            whereParams.addAll(namespaces.sorted())
        }

        selectParams += filter.timeTo
        selectParams += filter.timeTo
        selectParams += filter.timeTo

        groupedParams += filter.timeFrom
        groupedParams += filter.timeTo
        groupedParams += filter.timeFrom
        groupedParams += filter.timeTo
        groupedParams += filter.timeFrom
        groupedParams += filter.timeTo

        val sql = """
            WITH per_instance AS (
              SELECT
                lemline_workflow_id,
                MAX(lemline_workflow_namespace) AS lemline_workflow_namespace,
                MAX(lemline_workflow_name) AS lemline_workflow_name,
                MAX(lemline_workflow_version) AS lemline_workflow_version,
                MIN(CASE
                      WHEN type = '${LifecycleEventType.WORKFLOW_STARTED.type}' AND event_time < ?
                      THEN event_time
                    END) AS started_at,
                MAX(CASE
                      WHEN type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}' AND event_time < ?
                      THEN event_time
                    END) AS completed_at,
                MAX(CASE
                      WHEN type = '${LifecycleEventType.WORKFLOW_FAULTED.type}' AND event_time < ?
                      THEN event_time
                    END) AS faulted_at
              FROM $analyticsQualifiedTable
              WHERE ${where.joinToString(" AND ")}
              GROUP BY lemline_workflow_id
            ),
            per_instance_status AS (
              SELECT
                lemline_workflow_namespace,
                lemline_workflow_name,
                lemline_workflow_version,
                started_at,
                completed_at,
                faulted_at,
                CASE
                  WHEN started_at IS NULL THEN 'UNKNOWN'
                  WHEN completed_at IS NOT NULL AND faulted_at IS NOT NULL THEN
                    CASE
                      WHEN completed_at >= faulted_at THEN 'COMPLETED'
                      ELSE 'FAULTED'
                    END
                  WHEN completed_at IS NOT NULL THEN 'COMPLETED'
                  WHEN faulted_at IS NOT NULL THEN 'FAULTED'
                  ELSE 'RUNNING'
                END AS status_at_time_to
              FROM per_instance
            ),
            grouped AS (
              SELECT
                lemline_workflow_namespace,
                lemline_workflow_name,
                lemline_workflow_version,
                SUM(CASE WHEN started_at >= ? AND started_at < ? THEN 1 ELSE 0 END) AS started_count,
                SUM(CASE
                      WHEN status_at_time_to = 'COMPLETED'
                       AND completed_at >= ? AND completed_at < ?
                      THEN 1 ELSE 0
                    END) AS completed_count,
                SUM(CASE
                      WHEN status_at_time_to = 'FAULTED'
                       AND faulted_at >= ? AND faulted_at < ?
                      THEN 1 ELSE 0
                    END) AS faulted_count,
                SUM(CASE WHEN status_at_time_to = 'RUNNING' THEN 1 ELSE 0 END) AS running_count_at_time_to
              FROM per_instance_status
              WHERE started_at IS NOT NULL
              GROUP BY lemline_workflow_namespace, lemline_workflow_name, lemline_workflow_version
            )
        """.trimIndent()

        return SqlQuery(
            sql = sql,
            params = selectParams + whereParams + groupedParams,
        )
    }

}
