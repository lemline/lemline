// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.analytics

import com.lemline.runner.analytics.config.AnalyticsConfigConstants.ANALYTICS_POSTGRES_SCHEMA_DEFAULT
import com.lemline.runner.common.config.AnalyticsConfig
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_SCHEMA
import com.lemline.runner.common.config.SQLiteAnalyticsTime
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class LifecycleAnalyticsRepository(
    @ConfigProperty(name = LEMLINE_ANALYTICS_POSTGRES_SCHEMA, defaultValue = ANALYTICS_POSTGRES_SCHEMA_DEFAULT)
    schema: String,
) {
    @Inject
    private lateinit var analyticsConfig: AnalyticsConfig

    private val validatedSchema = validateIdentifier("schema", schema)
    private val validatedTable = validateIdentifier("table", ANALYTICS_LIFECYCLE_EVENTS_TABLE)
    private val qualifiedTable: String by lazy {
        val quotedTable = quoteIdentifier(validatedTable)

        when (analyticsConfig.analyticsType) {
            AnalyticsType.POSTGRESQL -> "${quoteIdentifier(validatedSchema)}.$quotedTable"
            AnalyticsType.H2, AnalyticsType.SQLITE -> quotedTable
        }
    }

    private val sqlite by lazy { analyticsConfig.analyticsType == AnalyticsType.SQLITE }

    private val insertSql: String by lazy {
        analyticsConfig.insertIgnoreInto(
            tableName = qualifiedTable,
            columns = """
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
            """.trimIndent() + if (sqlite) ", ingested_at" else "",
            values = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ${payloadValueExpression()}" + if (sqlite) ", ?" else ""
        )
    }

    suspend fun insert(row: LifecycleAnalyticsModel): IngestResult = analyticsConfig.withConnection { conn ->
        conn.prepareStatement(insertSql).use { stmt ->
            stmt.setString(1, row.eventId)
            stmt.setString(2, row.source)
            stmt.setString(3, row.type)
            stmt.setString(4, row.specVersion)
            stmt.setNullableString(5, row.subject)
            if (sqlite) {
                val eventTime = row.eventTime?.toInstant()?.let(SQLiteAnalyticsTime::encode)
                if (eventTime == null) stmt.setNull(6, Types.BIGINT) else stmt.setLong(6, eventTime)
            } else {
                stmt.setNullableOffsetDateTime(6, row.eventTime)
            }
            stmt.setNullableString(7, row.dataContentType)
            stmt.setNullableString(8, row.dataSchema)
            if (sqlite) {
                stmt.setNullableString(9, row.workflowId?.toString())
            } else {
                stmt.setNullableUuid(9, row.workflowId)
            }
            stmt.setNullableString(10, row.workflowNamespace)
            stmt.setNullableString(11, row.workflowName)
            stmt.setNullableString(12, row.workflowVersion)
            stmt.setString(13, row.payload)
            if (sqlite) stmt.setLong(14, SQLiteAnalyticsTime.encode(Instant.now()))

            if (stmt.executeUpdate() == 1) {
                IngestResult.INSERTED
            } else {
                IngestResult.DUPLICATE
            }
        }
    }

    private fun payloadValueExpression(): String = when (analyticsConfig.analyticsType) {
        AnalyticsType.POSTGRESQL -> "CAST(? AS JSONB)"
        AnalyticsType.H2, AnalyticsType.SQLITE -> "?"
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
