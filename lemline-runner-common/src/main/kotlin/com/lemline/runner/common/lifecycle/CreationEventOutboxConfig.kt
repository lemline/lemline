// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.runner.common.config.OutboxAndCleanupConfig

/**
 * Configuration of the creation event outbox (`lemline.outbox.creation-events`).
 * [enabled] is false when lifecycle events are not produced at all.
 * Implementations are provided by the main runner module.
 */
interface CreationEventOutboxConfig : OutboxAndCleanupConfig
