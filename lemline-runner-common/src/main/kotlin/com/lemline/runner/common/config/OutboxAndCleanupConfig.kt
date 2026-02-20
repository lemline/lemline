// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Combined configuration for a feature's outbox and cleanup.
 */
interface OutboxAndCleanupConfig {
    /**
     * Whether this feature is enabled
     */
    val enabled: Boolean

    /**
     * Outbox processing configuration
     */
    val outbox: OutboxConfig

    /**
     * Cleanup configuration
     */
    val cleanup: CleanupConfig
}

/**
 * Configuration interface for outbox processing.
 * Implementations are provided by the main runner module.
 */
interface OutboxConfig {
    /**
     * Processing interval
     */
    val every: Duration

    /**
     * Maximum number of messages to process in one batch
     */
    val batchSize: Int

    /**
     * Maximum random delay before first scheduled poll.
     * Used to desynchronize multiple workers starting simultaneously,
     * preventing thundering herd on the database.
     * Actual delay will be random between 0 and this value.
     */
    val initialJitter: Duration

    /**
     * Base delay for exponential backoff when retrying failed messages.
     * Each retry doubles the previous delay (e.g., 10s -> 20s -> 40s).
     * A ±20% jitter is applied to prevent synchronized retries.
     */
    val retryDelay: Duration

    /**
     * Maximum number of processing attempts
     */
    val maxAttempts: Int

    val randomInitialDelay: Duration
        get() = when (val millis = initialJitter.inWholeMilliseconds) {
            0L -> Duration.ZERO
            else -> Random.nextLong(millis).milliseconds
        }
}

/**
 * Configuration interface for cleanup operations.
 * Implementations are provided by the main runner module.
 */
interface CleanupConfig {
    /**
     * Cleanup interval
     */
    val every: Duration

    /**
     * Maximum random delay before first scheduled cleanup.
     * Used to desynchronize multiple workers starting simultaneously.
     * Actual delay will be random between 0 and this value.
     */
    val initialJitter: Duration?

    /**
     * Age of messages to clean up
     */
    val after: Duration

    /**
     * Maximum number of messages to clean up in one batch
     */
    val batchSize: Int

    val randomInitialDelay: Duration
        get() = when (val millis = initialJitter?.inWholeMilliseconds ?: 0L) {
            0L -> Duration.ZERO
            else -> Random.nextLong(millis).milliseconds
        }
}
