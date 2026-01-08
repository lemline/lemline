// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.runner.common.config.OutboxAndCleanupConfig
import kotlin.time.ExperimentalTime

/**
 * Configuration interface for the Retry feature.
 * Implementations are provided by the main runner module.
 */
@ExperimentalTime
interface RetryConfig : OutboxAndCleanupConfig
