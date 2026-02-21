// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.analytics

import com.lemline.common.values.WorkflowId
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Typed
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
@Typed(PostgresqlWorkflowAnalyticsEventSource::class)
class PostgresqlWorkflowAnalyticsEventSource(
    @ConfigProperty(name = "lemline.analytics.postgresql.schema", defaultValue = "public")
    schema: String,
    @ConfigProperty(name = "lemline.analytics.postgresql.table", defaultValue = "lemline_lifecycle_events")
    table: String,
) : WorkflowAnalyticsEventSource {

    @Inject
    @DataSource("analytics")
    lateinit var analyticsDataSource: Instance<AgroalDataSource>

    private val validatedSchema = validateIdentifier("schema", schema)
    private val validatedTable = validateIdentifier("table", table)
    private val qualifiedTable = "\"$validatedSchema\".\"$validatedTable\""

    override suspend fun listByWorkflowIdAfter(
        workflowId: WorkflowId,
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<WorkflowAnalyticsEventRow> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit must be > 0" }

        val sql = """
            SELECT id, type, payload::text AS payload_json
            FROM $qualifiedTable
            WHERE lemline_workflow_id = ?
              AND id > ?
            ORDER BY id ASC
            LIMIT ?
        """.trimIndent()

        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, workflowId.value.value)
                stmt.setLong(2, afterSequenceExclusive)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WorkflowAnalyticsEventRow(
                                    sequence = rs.getLong("id"),
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
        requireAnalyticsDataSource().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { _ -> }
            }
        }
    }

    private fun requireAnalyticsDataSource(): AgroalDataSource {
        if (analyticsDataSource.isResolvable) return analyticsDataSource.get()
        throw IllegalStateException(
            "Analytics datasource 'analytics' is not available. Configure lemline.analytics.postgresql.*"
        )
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
