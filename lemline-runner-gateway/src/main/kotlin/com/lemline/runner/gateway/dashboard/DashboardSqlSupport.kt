// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal object DashboardSqlSupport {
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
}

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
