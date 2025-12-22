// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.runner.common.config.OutboxAndCleanupConfig
import kotlin.time.ExperimentalTime

/**
 * Configuration interface for the Schedule feature.
 * Implementations are provided by the main runner module.
 */
@ExperimentalTime
interface ScheduleConfig : OutboxAndCleanupConfig
