// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand

/**
 * Interface for emitting workflow commands to the command channel.
 * Implementations are provided by the main runner module.
 */
interface CommandEmitter {
    /**
     * Sends an instance message to the command channel.
     *
     * @param message The instance message containing workflow state
     * @param idempotentKey Optional key for message deduplication
     */
    suspend fun send(message: InstanceMessage<out WorkflowCommand>, idempotentKey: IDV7? = null)
}
