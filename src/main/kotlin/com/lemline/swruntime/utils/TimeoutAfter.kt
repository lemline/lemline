package com.lemline.swruntime.utils

import io.serverlessworkflow.api.types.DurationInline
import io.serverlessworkflow.api.types.TimeoutAfter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal fun TimeoutAfter.toDuration() =
    when (val da = get()) {
        is String -> Duration.parse(da)
        is DurationInline -> da.toDuration()
        else -> error("Unknown TimeoutAfter: $da")
    }

internal fun DurationInline.toDuration(): Duration {
    return days.days + hours.hours + minutes.minutes + seconds.seconds + milliseconds.milliseconds
}