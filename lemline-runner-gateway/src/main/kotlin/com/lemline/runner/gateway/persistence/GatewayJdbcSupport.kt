// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.persistence

import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.SQLiteAnalyticsTime
import com.lemline.runner.common.repositories.ops.bindTimestamp
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal object GatewayJdbcSupport {
    private val identifierRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validateIdentifier(kind: String, value: String): String {
        require(identifierRegex.matches(value)) {
            "Invalid analytics $kind '$value'. Only [A-Za-z_][A-Za-z0-9_]* is allowed."
        }
        return value
    }

    fun qualifiedTable(schema: String, table: String): String {
        val validatedSchema = validateIdentifier("schema", schema)
        val validatedTable = validateIdentifier("table", table)
        return "\"$validatedSchema\".\"$validatedTable\""
    }

    fun qualifiedAnalyticsTable(
        analyticsType: AnalyticsType,
        schema: String,
        table: String
    ): String = when (analyticsType) {
        AnalyticsType.POSTGRESQL -> qualifiedTable(schema, table)
        AnalyticsType.H2, AnalyticsType.SQLITE -> quotedIdentifier("table", table)
    }

    private fun quotedIdentifier(kind: String, value: String): String {
        val validatedValue = validateIdentifier(kind, value)
        return "\"$validatedValue\""
    }
}

internal fun lifecycleTypeSqlList(vararg types: LifecycleEventType): String =
    types.flatMap { it.acceptedTypes }.joinToString(", ") { "'$it'" }

internal fun ResultSet.getInstantOrNull(column: String): Instant? {
    val raw = getObject(column) ?: return null
    return when (raw) {
        is Instant -> raw
        is OffsetDateTime -> raw.toInstant()
        is Timestamp -> raw.toInstant()
        is LocalDateTime -> raw.toInstant(ZoneOffset.UTC)
        else -> getTimestamp(column)?.toInstant()
    }
}

internal fun PreparedStatement.setGatewayObject(index: Int, value: Any) {
    when (value) {
        is Instant -> setTimestamp(index, Timestamp.from(value))
        else -> setObject(index, value)
    }
}

internal fun ResultSet.getAnalyticsInstant(column: String, type: AnalyticsType): Instant? =
    if (type == AnalyticsType.SQLITE) {
        getLong(column).takeUnless { wasNull() }?.let(SQLiteAnalyticsTime::decode)
    } else {
        getInstantOrNull(column)
    }

internal fun ResultSet.getAnalyticsUuid(column: String, type: AnalyticsType): UUID =
    if (type == AnalyticsType.SQLITE) UUID.fromString(getString(column)) else getObject(column, UUID::class.java)

internal fun PreparedStatement.setAnalyticsObject(index: Int, value: Any, type: AnalyticsType) {
    if (type == AnalyticsType.SQLITE) {
        when (value) {
            is Instant -> setLong(index, SQLiteAnalyticsTime.encode(value))
            is UUID -> setString(index, value.toString())
            else -> setObject(index, value)
        }
    } else {
        setGatewayObject(index, value)
    }
}

/** Bind timestamps using the orchestration database's storage format. */
internal fun PreparedStatement.setGatewayObject(index: Int, value: Any, databaseConfig: DatabaseConfig) {
    when (value) {
        is Instant -> databaseConfig.bindTimestamp(this, index, Timestamp.from(value))
        else -> setObject(index, value)
    }
}
