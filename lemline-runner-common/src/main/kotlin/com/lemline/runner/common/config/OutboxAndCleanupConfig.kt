// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Combined configuration for a feature's outbox and cleanup.
 */
@ExperimentalTime
interface OutboxAndCleanupConfig {
    /**
     * Whether this feature is enabled
     */
    val enabled: Boolean

    /**
     * Outbox processing configuration (null if outbox not supported)
     */
    val outbox: OutboxConfig?

    /**
     * Cleanup configuration
     */
    val cleanup: CleanupConfig
}

/**
 * Configuration interface for outbox processing.
 * Implementations are provided by the main runner module.
 */
@ExperimentalTime
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
     * Initial delay before starting processing
     */
    val initialDelay: Duration

    /**
     * Maximum number of processing attempts
     */
    val maxAttempts: Int
}

/**
 * Configuration interface for cleanup operations.
 * Implementations are provided by the main runner module.
 */
@ExperimentalTime
interface CleanupConfig {
    /**
     * Cleanup interval
     */
    val every: Duration

    /**
     * Age of messages to clean up
     */
    val after: Duration

    /**
     * Maximum number of messages to clean up in one batch
     */
    val batchSize: Int
}
