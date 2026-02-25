// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.ANALYTICS_TYPE_DEFAULT
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Typed
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
@Typed(PostgresqlWorkflowAnalyticsEventSource::class)
class PostgresqlWorkflowAnalyticsEventSource(
    config: LemlineConfiguration,
    @ConfigProperty(name = LEMLINE_ANALYTICS_TYPE, defaultValue = ANALYTICS_TYPE_DEFAULT)
    analyticsType: String,
) : WorkflowAnalyticsEventSource {

    @Inject
    lateinit var analyticsDataSourceProvider: AnalyticsDataSourceProvider

    private val resolvedAnalyticsType = AnalyticsType.fromConfigValue(analyticsType)
    private val validatedTable = validateIdentifier("table", ANALYTICS_LIFECYCLE_EVENTS_TABLE)
    private val qualifiedTable = when (resolvedAnalyticsType) {
        AnalyticsType.POSTGRESQL -> {
            val validatedSchema = validateIdentifier("schema", config.analyticsSchemaResolved)
            "\"$validatedSchema\".\"$validatedTable\""
        }
        AnalyticsType.H2 -> "\"$validatedTable\""
    }
    private val payloadProjection = when (resolvedAnalyticsType) {
        AnalyticsType.POSTGRESQL -> "payload::text"
        AnalyticsType.H2 -> "payload"
    }

    override suspend fun listByWorkflowIdAfter(
        workflowId: WorkflowId,
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<WorkflowAnalyticsEventRow> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit must be > 0" }

        val sql = """
            SELECT id, type, $payloadProjection AS payload_json
            FROM $qualifiedTable
            WHERE lemline_workflow_id = ?
              AND id > ?
            ORDER BY id ASC
            LIMIT ?
        """.trimIndent()

        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, workflowId.value.value)
                stmt.setLong(2, afterSequenceExclusive)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WorkflowAnalyticsEventRow(
                                    cursor = rs.getLong("id"),
                                    eventType = rs.getString("type"),
                                    cloudEventJson = rs.getString("payload_json")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun validate(): Unit = withContext(Dispatchers.IO) {
        val sql = "SELECT id FROM $qualifiedTable ORDER BY id DESC LIMIT 1"
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { _ -> }
            }
        }
    }

    private fun validateIdentifier(kind: String, value: String): String {
        require(IDENTIFIER_REGEX.matches(value)) {
            "Invalid analytics $kind '$value'. Only [A-Za-z_][A-Za-z0-9_]* is allowed."
        }
        return value
    }

    companion object {
        private val IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}
