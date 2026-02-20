// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import com.lemline.common.logger.logger
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime

/**
 * Service for handling wait-related workflow events.
 *
 * Provides business logic for starting waits by persisting wait state to the database.
 * The wait outbox processor will later resume the workflow when the wait period ends.
 */
@ExperimentalTime
@ApplicationScoped
class WaitService {

    @Inject
    lateinit var waitRepository: WaitRepository

    private val logger = logger()

    /**
     * Handles wait started by persisting the wait state to the database.
     *
     * @param instance The instance message containing the wait started event
     * @return true if the wait was created, false if it already existed (idempotent)
     */
    suspend fun handleWaitStarted(instance: InstanceMessage<WorkflowEvent.WaitStarted>) {
        // Derive wait from node stack (idempotent)
        val wait = WaitModel(instanceMessage = instance)

        val rowsInserted = waitRepository.insert(wait)

        if (rowsInserted == 0) logger.warn { "Wait model already exists (idempotent insert): $wait" }
    }
}
