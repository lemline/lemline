// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.runner.common.config.OutboxAndCleanupConfig

/**
 * Configuration interface for the Schedule feature.
 * Implementations are provided by the main runner module.
 */
interface ScheduleConfig : OutboxAndCleanupConfig
