package com.lemline.sw.utils

import io.serverlessworkflow.api.types.RetryPolicyJitter
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

internal fun RetryPolicyJitter?.toRandomDuration(): Duration {
    if (this == null) return 0.seconds

    return when (to) {
        null -> when (from) {
            null -> return 0.seconds
            else -> error("Jitter can not be defined from '${from.toDuration()}' to '$to'")
        }

        else -> when (from) {
            null -> getRandomDuration(0.seconds, to.toDuration())
            else -> getRandomDuration(from.toDuration(), to.toDuration())
        }
    }
}

private fun getRandomDuration(from: Duration, to: Duration): Duration {
    if (from > to) error("Jitter can not be defined from '$from' to '$to'")
    val randomSeconds = Random.nextDouble(from.toDouble(DurationUnit.SECONDS), to.toDouble(DurationUnit.SECONDS))
    return randomSeconds.seconds
}