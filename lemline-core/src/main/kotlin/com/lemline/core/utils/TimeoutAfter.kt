// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import io.serverlessworkflow.api.types.DurationInline
import io.serverlessworkflow.api.types.TimeoutAfter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Converts a TimeoutAfter object to a Duration.
 */
internal fun TimeoutAfter.toDuration() = when (val da = get()) {
    is String -> Duration.parse(da)
    is DurationInline -> da.toDuration()
    else -> error("Unknown TimeoutAfter: $da")
}

internal fun DurationInline.toDuration(): Duration =
    days.days + hours.hours + minutes.minutes + seconds.seconds + milliseconds.milliseconds
