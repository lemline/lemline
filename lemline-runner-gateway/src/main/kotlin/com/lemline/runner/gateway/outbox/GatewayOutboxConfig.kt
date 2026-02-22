// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.runner.common.config.OutboxAndCleanupConfig

/**
 * Configuration interface for the Gateway command outbox feature.
 * Implementations are provided by the main runner module.
 */
interface GatewayOutboxConfig : OutboxAndCleanupConfig
