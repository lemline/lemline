// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.analytics.config.AnalyticsConfigConstants.ANALYTICS_POSTGRES_SCHEMA_DEFAULT
import com.lemline.runner.analytics.config.AnalyticsConfigConstants.ANALYTICS_POSTGRES_TABLE_DEFAULT
import com.lemline.runner.common.config.ANALYTICS_CONSUMER_ENABLED
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class LifecycleAnalyticsRepository(
    @ConfigProperty(name = "lemline.analytics.postgresql.schema", defaultValue = ANALYTICS_POSTGRES_SCHEMA_DEFAULT)
    schema: String,
    @ConfigProperty(name = "lemline.analytics.postgresql.table", defaultValue = ANALYTICS_POSTGRES_TABLE_DEFAULT)
    table: String,
) {
    @Inject
    @DataSource("analytics")
    internal lateinit var analyticsDataSource: Instance<AgroalDataSource>

    private val validatedSchema = validateIdentifier("schema", schema)
    private val validatedTable = validateIdentifier("table", table)

    private val qualifiedTable = "${quoteIdentifier(validatedSchema)}.${quoteIdentifier(validatedTable)}"

    private val insertSql = """
        INSERT INTO $qualifiedTable (
            event_id,
            source,
            type,
            specversion,
            subject,
            event_time,
            datacontenttype,
            dataschema,
            lemline_workflow_id,
            lemline_workflow_namespace,
            lemline_workflow_name,
            lemline_workflow_version,
            payload
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
        ON CONFLICT (source, event_id) DO NOTHING
    """.trimIndent()

    suspend fun insert(row: LifecycleAnalyticsModel): IngestResult = withContext(Dispatchers.IO) {
        val dataSource = requireAnalyticsDataSource()

        dataSource.connection.use { conn ->
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setString(1, row.eventId)
                stmt.setString(2, row.source)
                stmt.setString(3, row.type)
                stmt.setString(4, row.specVersion)
                stmt.setNullableString(5, row.subject)
                stmt.setNullableOffsetDateTime(6, row.eventTime)
                stmt.setNullableString(7, row.dataContentType)
                stmt.setNullableString(8, row.dataSchema)
                stmt.setNullableUuid(9, row.workflowId)
                stmt.setNullableString(10, row.workflowNamespace)
                stmt.setNullableString(11, row.workflowName)
                stmt.setNullableString(12, row.workflowVersion)
                stmt.setString(13, row.payload)

                if (stmt.executeUpdate() == 1) {
                    IngestResult.INSERTED
                } else {
                    IngestResult.DUPLICATE
                }
            }
        }
    }

    private fun requireAnalyticsDataSource(): AgroalDataSource {
        if (analyticsDataSource.isResolvable) return analyticsDataSource.get()

        throw IllegalStateException(
            "Analytics datasource 'analytics' is not available. " +
                "Enable '$ANALYTICS_CONSUMER_ENABLED' and configure lemline.analytics.postgresql.*"
        )
    }

    private fun validateIdentifier(kind: String, value: String): String {
        require(IDENTIFIER_REGEX.matches(value)) {
            "Invalid analytics $kind '$value'. Only [A-Za-z_][A-Za-z0-9_]* is allowed."
        }
        return value
    }

    private fun quoteIdentifier(value: String): String = "\"$value\""

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) {
            setNull(index, Types.VARCHAR)
        } else {
            setString(index, value)
        }
    }

    private fun PreparedStatement.setNullableOffsetDateTime(index: Int, value: java.time.OffsetDateTime?) {
        if (value == null) {
            setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
        } else {
            setObject(index, value)
        }
    }

    private fun PreparedStatement.setNullableUuid(index: Int, value: java.util.UUID?) {
        if (value == null) {
            setNull(index, Types.OTHER)
        } else {
            setObject(index, value)
        }
    }

    companion object {
        private val IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}
