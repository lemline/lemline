// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instanceevents

import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import com.lemline.runner.config.analyticsTypeResolved
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import com.lemline.runner.gateway.persistence.GatewayJdbcSupport
import com.lemline.runner.gateway.persistence.getAnalyticsInstant
import com.lemline.runner.gateway.persistence.getAnalyticsUuid
import com.lemline.runner.gateway.persistence.normalizePageSize
import com.lemline.runner.gateway.persistence.setAnalyticsObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class InstanceEventQueryService(
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
        AnalyticsType.H2, AnalyticsType.SQLITE -> "payload"
    }

    suspend fun listEvents(filter: InstanceEventFilter): InstanceEventListResult = withContext(Dispatchers.IO) {
        val normalizedPageSize = normalizePageSize(filter.pageSize)
        val afterWhere = if (filter.afterSequenceExclusive == null) "" else "AND id > ?"
        val sql = """
            SELECT
              id,
              lemline_workflow_id,
              lemline_workflow_namespace,
              type,
              event_time,
              $payloadTextProjection AS payload_json
            FROM $analyticsQualifiedTable
            WHERE lemline_workflow_id = ?
            $afterWhere
            ORDER BY id ASC
            LIMIT ?
        """.trimIndent()

        val rows = analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setAnalyticsObject(1, filter.instanceId, analyticsType)
                var nextIndex = 2
                filter.afterSequenceExclusive?.let {
                    stmt.setLong(nextIndex++, it)
                }
                stmt.setInt(nextIndex, normalizedPageSize + 1)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                InstanceEventRow(
                                    cursor = rs.getLong("id"),
                                    instanceId = rs.getAnalyticsUuid("lemline_workflow_id", analyticsType),
                                    eventType = rs.getString("type"),
                                    eventTime = rs.getAnalyticsInstant("event_time", analyticsType),
                                    cloudEventJson = rs.getString("payload_json") ?: "{}",
                                    namespace = rs.getString("lemline_workflow_namespace"),
                                )
                            )
                        }
                    }
                }
            }
        }

        val hasMore = rows.size > normalizedPageSize
        val pageRows = if (hasMore) rows.take(normalizedPageSize) else rows

        InstanceEventListResult(
            events = pageRows,
            nextCursor = pageRows.lastOrNull()?.cursor?.takeIf { hasMore },
            latestCursor = latestEventSequence(filter.instanceId),
        )
    }

    suspend fun latestEventSequence(instanceId: UUID, snapshotSequence: Long? = null): Long? =
        withContext(Dispatchers.IO) {
            val snapshotWhere = if (snapshotSequence == null) "" else "AND id <= ?"
            val sql = """
            SELECT MAX(id) AS max_id
            FROM $analyticsQualifiedTable
            WHERE lemline_workflow_id = ?
            $snapshotWhere
        """.trimIndent()

            analyticsDataSourceProvider.require().connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setAnalyticsObject(1, instanceId, analyticsType)
                    if (snapshotSequence != null) {
                        stmt.setLong(2, snapshotSequence)
                    }
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong("max_id").takeIf { !rs.wasNull() } else null
                    }
                }
            }
        }

    suspend fun firstEvent(instanceId: UUID): InstanceEventRow? =
        listEvents(InstanceEventFilter(instanceId = instanceId, pageSize = 1)).events.firstOrNull()
}
