// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Configuration interface for the definitions feature module.
 * Controls the definition cache synchronization behavior.
 *
 * Implementations should provide configuration for:
 * - Whether the definition cache sync is enabled
 * - Sync interval for loading definitions from database to cache
 */
@ExperimentalTime
interface DefinitionConfig {
    /**
     * Whether the definition cache sync is enabled.
     * Typically enabled when CloudEvents consumer is active (listen feature).
     */
    val enabled: Boolean

    /**
     * How often to sync definitions from database to cache.
     */
    val syncEvery: Duration
}
