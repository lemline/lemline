// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import java.time.Instant

/** SQLite projects timestamps as signed epoch microseconds. */
object SQLiteAnalyticsTime {
    private const val MICROS_PER_SECOND = 1_000_000L

    fun encode(instant: Instant): Long {
        val seconds = instant.epochSecond
        val micros = instant.nano / 1_000L
        return if (seconds < 0) {
            Math.subtractExact(Math.multiplyExact(seconds + 1, MICROS_PER_SECOND), MICROS_PER_SECOND - micros)
        } else {
            Math.addExact(Math.multiplyExact(seconds, MICROS_PER_SECOND), micros)
        }
    }

    fun decode(micros: Long): Instant = Instant.ofEpochSecond(
        Math.floorDiv(micros, MICROS_PER_SECOND),
        Math.floorMod(micros, MICROS_PER_SECOND) * 1_000,
    )
}
