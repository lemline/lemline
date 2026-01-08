// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.forks

import com.lemline.runner.common.config.CleanupConfig
import kotlin.time.ExperimentalTime

/**
 * Configuration interface for the Fork feature.
 * Fork feature only needs cleanup config (no outbox processing).
 * Implementations are provided by the main runner module.
 */
@ExperimentalTime
interface ForkFeatureConfig {
    val enabled: Boolean
    val cleanup: CleanupConfig
}
