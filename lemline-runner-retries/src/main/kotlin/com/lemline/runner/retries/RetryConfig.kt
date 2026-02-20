// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.runner.common.config.OutboxAndCleanupConfig

/**
 * Configuration interface for the Retry feature.
 * Implementations are provided by the main runner module.
 */
interface RetryConfig : OutboxAndCleanupConfig
