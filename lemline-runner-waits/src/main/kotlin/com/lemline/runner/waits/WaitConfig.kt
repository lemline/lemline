// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import com.lemline.runner.common.config.OutboxAndCleanupConfig
import kotlin.time.ExperimentalTime

/**
 * Configuration interface for the Wait feature.
 * Implementations are provided by the main runner module.
 */
@ExperimentalTime
interface WaitConfig : OutboxAndCleanupConfig
