// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.util.*
import java.util.regex.Pattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val DURATION_PATTERN: Pattern = Pattern.compile("^(\\d+)([smhd])$")

/**
 * A simple duration converter that converts strings like "30s", "5m", "2h", "7d" into [Duration] objects.
 * Supported units are seconds (s), minutes (m), hours (h), and days (d).
 */
fun String.toDuration(): Duration {
    require(this.trim { it <= ' ' }.isNotEmpty()) { "Duration value is null or empty" }

    val matcher = DURATION_PATTERN.matcher(this.trim { it <= ' ' }.lowercase(Locale.getDefault()))
    require(matcher.matches()) { "Invalid duration format: $this" }

    val amount = matcher.group(1).toLong()
    return when (val unit = matcher.group(2)) {
        "s" -> amount.seconds
        "m" -> amount.minutes
        "h" -> amount.hours
        "d" -> amount.days
        else -> throw IllegalArgumentException("Unknown duration unit: $unit")
    }
}
