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
import java.sql.Connection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.annotations.VisibleForTesting

/**
 * Processes listener timeouts by emitting failure commands for listeners that have exceeded
 * their configured timeout.
 *
 * ## Processing Flow
 *
 * 1. **Find Timed Out**: Query listeners where `timeout_at < NOW()` and not yet completed/faulted
 * 2. **Create Error**: Build a timeout error with type, status 408, and details
 * 3. **Emit Command**: Send `ResumeWithFaultedTask` command to continue the workflow with error
 * 4. **Mark Faulted**: Update listener status to faulted in database
 *
 * This processor runs on a scheduled interval and processes timed-out listeners in batches.
 */
@Startup
@ApplicationScoped
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

    override val initialDelay: Duration by lazy {
        listenerConfig.outbox?.randomInitialDelay ?: Duration.ZERO
    }

    /** Batch size for processing */
    private val batchSize by lazy {
        listenerConfig.outbox?.batchSize ?: 100
    }

    private companion object {
        const val SAVEPOINT = "listener_timeout"
    }

    /**
     * Find and process timed-out listeners in batches.
     */
    @VisibleForTesting
    public override suspend fun doWork() {
        var totalProcessed = 0
        var batchNumber = 0

        do {
            batchNumber++
            var processed = 0

            // One write transaction arbitrates between this timeout and the listener's completion,
            // on SQLite too: it keeps the writer while each failure command is published. A
            // completion relay that already claimed the listener hides it from findTimedOut, and
            // one that comes later finds the listener failed.
            databaseConfig.withTransaction { connection ->
                val timedOut = listenerRepository.findTimedOut(batchSize, connection)

                if (timedOut.isNotEmpty()) {
                    for (listener in timedOut) {
                        // A savepoint keeps one listener's failure from aborting the shared
                        // transaction and rolling back the listeners already processed.
                        connection.createStatement().use { it.execute("SAVEPOINT $SAVEPOINT") }
                        try {
                            processTimedOutListener(listener, connection)
                            connection.createStatement().use { it.execute("RELEASE SAVEPOINT $SAVEPOINT") }
                            processed++
                        } catch (e: Exception) {
                            connection.createStatement().use { it.execute("ROLLBACK TO SAVEPOINT $SAVEPOINT") }
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
    private suspend fun processTimedOutListener(listener: ListenerModel, connection: Connection) {
        val listenStarted = listener.instanceMessage.workflowState

        // Create timeout error
        val error = InternalException.Error(
            errorType = WorkflowErrorType.TIMEOUT,
            position = listenStarted.nodePosition,
            title = "Listen task timed out",
            detail = "Listener at position ${listenStarted.nodePosition} timed out while waiting for events. " +
                "Timeout was set to ${listener.timeoutAt}."
        )

        // Create and emit the failure command
        val resumeCommand = listenStarted.resumeFaulted(error)
        val resumeMessage = InstanceMessage(
            workflowInfo = listener.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        val idempotentKey = listenStarted.nodeStack.deriveIdempotentId("-listen-timeout")

        commandEmitter.send(resumeMessage, idempotentKey)

        // Mark listener as faulted
        listenerRepository.markFailed(
            id = listener.id,
            errorClass = "ListenerTimeoutException",
            errorMessage = "Listen task timed out at ${listener.timeoutAt}",
            errorStackTrace = null,
            connection = connection,
        )

        logger.info {
            "Listener ${listener.id} timed out for workflow ${listener.workflowId} " +
                "at position ${listener.nodePosition}"
        }
    }
}
