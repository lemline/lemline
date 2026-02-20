// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.runner.common.config.OutboxAndCleanupConfig

/**
 * Configuration interface for the listeners feature module.
 * Extends [OutboxAndCleanupConfig] to provide outbox and cleanup configuration.
 *
 * Implementations should provide configuration for:
 * - Whether the listener feature is enabled
 * - Outbox processing parameters (batch size, intervals, retry settings)
 * - Cleanup parameters (retention period, batch size)
 */
interface ListenerConfig : OutboxAndCleanupConfig
