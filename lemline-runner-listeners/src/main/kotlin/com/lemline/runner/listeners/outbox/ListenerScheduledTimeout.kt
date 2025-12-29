// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners.outbox

import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.scheduled.AbstractScheduledTask
import com.lemline.runner.listeners.ListenerConfig
import com.lemline.runner.listeners.ListenerModel
import com.lemline.runner.listeners.ListenerRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Processes listener timeouts by emitting failure commands for listeners that have exceeded
 * their configured timeout.
 *
 * ## Processing Flow
 *
 * 1. **Find Timed Out**: Query listeners where `timeout_at < NOW()` and not yet completed/failed
 * 2. **Create Error**: Build a timeout error with type, status 408, and details
 * 3. **Emit Command**: Send `ResumeWithFailedTask` command to continue the workflow with error
 * 4. **Mark Failed**: Update listener status to failed in database
 *
 * This processor runs on a scheduled interval and processes timed-out listeners in batches.
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ListenerScheduledTimeout : AbstractScheduledTask() {

    override val jobName = "Listener scheduled timeout"

    @Inject
    lateinit var listenerConfig: ListenerConfig

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    @Inject
    private lateinit var commandEmitter: CommandEmitter

    @Inject
    private lateinit var databaseConfig: DatabaseConfig

    /** Is this processor enabled? */
    override val enabled by lazy { listenerConfig.enabled }

    /** Processing interval */
    override val interval: Duration by lazy {
        listenerConfig.outbox?.every ?: 5.seconds
    }

    /** Batch size for processing */
    private val batchSize by lazy {
        listenerConfig.outbox?.batchSize ?: 100
    }

    /**
     * Find and process timed-out listeners in batches.
     */
    override suspend fun doWork() {
        var totalProcessed = 0
        var batchNumber = 0

        do {
            batchNumber++
            var processed = 0

            databaseConfig.withTransaction { connection ->
                val timedOut = listenerRepository.findTimedOut(batchSize, connection)

                if (timedOut.isNotEmpty()) {
                    for (listener in timedOut) {
                        try {
                            processTimedOutListener(listener)
                            processed++
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to process timed out listener ${listener.id}" }
                        }
                    }
                    totalProcessed += processed
                }
            }
        } while (processed >= batchSize)

        if (totalProcessed > 0) {
            logger.debug { "Processed $totalProcessed timed out listener(s) in $batchNumber batch(es)" }
        }
    }

    /**
     * Process a single timed-out listener by emitting a failure command.
     */
    private suspend fun processTimedOutListener(listener: ListenerModel) {
        val listenStarted = listener.instanceMessage.workflowState

        // Create timeout error
        val error = InternalException.Error(
            errorType = WorkflowErrorType.TIMEOUT,
            position = listenStarted.nodePosition,
            title = "Listen task timed out",
            details = "Listener at position ${listenStarted.nodePosition} timed out while waiting for events. " +
                "Timeout was set to ${listener.timeoutAt}."
        )

        // Create and emit the failure command
        val resumeCommand = listenStarted.resumeFailed(error)
        val resumeMessage = InstanceMessage(
            workflowInfo = listener.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        val idempotentKey = listenStarted.nodeStack.deriveIdempotentId("-listen-timeout")

        commandEmitter.send(resumeMessage, idempotentKey)

        // Mark listener as failed
        listenerRepository.markFailed(
            id = listener.id,
            errorClass = "ListenerTimeoutException",
            errorMessage = "Listen task timed out at ${listener.timeoutAt}",
            errorStackTrace = null
        )

        logger.info {
            "Listener ${listener.id} timed out for workflow ${listener.workflowId} " +
                "at position ${listener.nodePosition}"
        }
    }
}
