// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.persistence

internal data class SqlQuery(
    val sql: String,
    val params: List<Any>,
)

internal fun normalizePageSize(rawPageSize: Int): Int =
    rawPageSize.takeIf { it > 0 }?.coerceAtMost(200) ?: 50

internal fun placeholders(size: Int): String =
    List(size) { "?" }.joinToString(", ")
