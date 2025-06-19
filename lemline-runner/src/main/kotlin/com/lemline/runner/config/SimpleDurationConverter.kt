// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.time.Duration
import java.util.*
import java.util.regex.Pattern
import org.eclipse.microprofile.config.spi.Converter

/**
 * Converter for parsing duration strings in the format of "1s", "2m", "3h", or "4d".
 */
private class SimpleDurationConverter : Converter<Duration> {
    override fun convert(value: String): Duration {
        require(value.trim { it <= ' ' }.isNotEmpty()) { "Duration value is null or empty" }

        val matcher = DURATION_PATTERN.matcher(value.trim { it <= ' ' }.lowercase(Locale.getDefault()))
        require(matcher.matches()) { "Invalid duration format: $value" }

        val amount = matcher.group(1).toLong()
        return when (val unit = matcher.group(2)) {
            "s" -> Duration.ofSeconds(amount)
            "m" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            "d" -> Duration.ofDays(amount)
            else -> throw IllegalArgumentException("Unknown duration unit: $unit")
        }
    }

    companion object {
        private val DURATION_PATTERN: Pattern = Pattern.compile("^(\\d+)([smhd])$")
    }
}

internal fun String.toDuration(): Duration = SimpleDurationConverter().convert(this)
