// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.runner.common.config.CleanupConfig

/**
 * Configuration interface for the Parent feature.
 * Parent feature only needs cleanup config (no outbox processing).
 * Implementations are provided by the main runner module.
 */
interface ParentFeatureConfig {
    val enabled: Boolean
    val cleanup: CleanupConfig
}
