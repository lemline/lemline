// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import com.lemline.common.logger.logger
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.messaging.InstanceMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Service for handling retry-related workflow events.
 *
 * Provides business logic for scheduling task retries by persisting retry state to the database.
 * The retry outbox processor will later resume the workflow when the retry is due.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class RetryService {

    @Inject
    lateinit var retryRepository: RetryRepository

    private val logger = logger()

    /**
     * Handles retry scheduled by persisting the retry state to the database.
     *
     * @param instance The instance message containing the retry scheduled event
     * @return true if the retry was created, false if it already existed (idempotent)
     */
    suspend fun handleRetryScheduled(instance: InstanceMessage<WorkflowEvent.TaskRetryScheduled>): Boolean {
        val retryId = instance.workflowState.nodeStack.deriveIdempotentId("-retry")
        val rowsInserted = retryRepository.insert(
            RetryModel.from(
                id = retryId,
                instance = instance,
                scheduledFor = instance.workflowState.retryAt,
                error = IllegalStateException("Task failed and will be retried"), // TODO: This is not the correct exception
                reason = "Task retry"
            )
        )
        if (rowsInserted == 0) {
            logger.info { "Retry model $retryId already exists (idempotent insert)" }
            return false
        }
        return true
    }
}
