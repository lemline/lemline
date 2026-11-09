// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent

/**
 * Interface for emitting workflow events to the events channel.
 * Implementations are provided by the main runner module.
 */
interface EventEmitter {
    /**
     * Sends an instance message to the events channel.
     *
     * @param message The instance message containing workflow state
     * @param idempotentKey Optional key for message deduplication
     */
    suspend fun send(message: InstanceMessage<WorkflowEvent>, idempotentKey: IDV7? = null)
}
