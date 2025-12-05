// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.json.LemlineJson
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.scheduled.AbstractScheduledTask
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
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
internal class ListenerTimeoutOutbox : AbstractScheduledTask() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    @Inject
    private lateinit var commandEmitter: WorkflowCommandEmitter

    override val taskName = "Listener timeout processor"

    /** Is this processor enabled? */
    override val enabled by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.enabled()?.getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    /** Processing interval */
    override val interval: Duration by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.outbox()?.every ?: 10.seconds
    }

    /** Batch size for processing */
    private val batchSize by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.outbox()?.batchSize ?: 100
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

            listenerRepository.withTransaction { connection ->
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

        val payload = LemlineJson.encodeToString(resumeMessage)
        val idempotentKey = listenStarted.nodeStack.deriveIdempotentId("-listen-timeout")

        commandEmitter.sendPayload(payload, idempotentKey)

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
