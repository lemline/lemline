// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import com.lemline.runner.config.analyticsTypeResolved
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class StatsQueryService(
    config: LemlineConfiguration,
) {

    @Inject
    lateinit var analyticsDataSourceProvider: AnalyticsDataSourceProvider

    private val analyticsQualifiedTable =
        DashboardSqlSupport.qualifiedAnalyticsTable(
            analyticsType = AnalyticsType.fromConfigValue(config.analyticsTypeResolved),
            schema = config.analyticsSchemaResolved,
            table = ANALYTICS_LIFECYCLE_EVENTS_TABLE
        )

    suspend fun queryStats(filter: StatsQueryFilter): List<DefinitionStatsRow> = withContext(Dispatchers.IO) {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()

        where += "type IN ('${LifecycleEventType.WORKFLOW_STARTED.type}', '${LifecycleEventType.WORKFLOW_COMPLETED.type}', '${LifecycleEventType.WORKFLOW_FAULTED.type}')"

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
        filter.timeFrom?.let {
            where += "event_time >= ?"
            params += it
        }
        filter.timeTo?.let {
            where += "event_time <= ?"
            params += it
        }

        val sql = """
            SELECT
              lemline_workflow_namespace,
              lemline_workflow_name,
              lemline_workflow_version,
              COUNT(*) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_STARTED.type}') AS started,
              COUNT(*) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_COMPLETED.type}') AS completed,
              COUNT(*) FILTER (WHERE type = '${LifecycleEventType.WORKFLOW_FAULTED.type}') AS faulted
            FROM $analyticsQualifiedTable
            WHERE ${where.joinToString(" AND ")}
            GROUP BY lemline_workflow_namespace, lemline_workflow_name, lemline_workflow_version
            ORDER BY lemline_workflow_namespace, lemline_workflow_name, lemline_workflow_version DESC
        """.trimIndent()

        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, value ->
                    stmt.setDashboardObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val started = rs.getLong("started")
                            val completed = rs.getLong("completed")
                            val faulted = rs.getLong("faulted")
                            add(
                                DefinitionStatsRow(
                                    namespace = rs.getString("lemline_workflow_namespace") ?: "",
                                    name = rs.getString("lemline_workflow_name") ?: "",
                                    version = rs.getString("lemline_workflow_version"),
                                    started = started,
                                    completed = completed,
                                    faulted = faulted,
                                    running = (started - completed - faulted).coerceAtLeast(0L),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

}
