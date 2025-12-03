// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerRepository
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.optionals.getOrNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
internal class ListenerTimeoutOutbox {
    private val logger = logger()

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    @Inject
    private lateinit var commandEmitter: WorkflowCommandEmitter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processingExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val processing = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    private val gracePeriod = 5000L

    /** Is this processor enabled? */
    private val enabled by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.enabled()?.getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    /** Processing configuration */
    private val processingConf by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.outbox()
    }

    @PostConstruct
    fun init() {
        if (!enabled) {
            logger.debug { "🚫 Listener timeout processor disabled by config" }
            return
        }

        processingConf?.every?.inWholeSeconds?.let { period ->
            processingExecutor.scheduleAtFixedRate(
                { scope.launch { processTimeouts() } },
                0,
                period,
                TimeUnit.SECONDS
            )
            logger.info { "⏱️ Listener timeout processing scheduled every ${period}s" }
        }
    }

    /**
     * Process timed-out listeners.
     */
    private suspend fun processTimeouts() {
        if (isShuttingDown.get()) {
            logger.debug { "⏹️ Skipping timeout processing: shutdown in progress" }
            return
        }

        if (!processing.compareAndSet(false, true)) {
            logger.warn { "⏭ Skipping scheduled timeout processing: previous execution still running" }
            return
        }

        try {
            doProcessTimeouts()
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during listener timeout processing" }
        } finally {
            processing.set(false)
        }
    }

    /**
     * Find and process timed-out listeners in batches.
     */
    private suspend fun doProcessTimeouts() {
        val batchSize = processingConf?.batchSize ?: 100
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
                "at position ${listener.workflowPosition}"
        }
    }

    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.info { "🛑 ShutdownEvent received - initiating graceful shutdown" }
        performGracefulShutdown(gracePeriod)
    }

    private fun performGracefulShutdown(timeoutMs: Long) {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.info { "🛑 Shutdown already in progress - ignoring" }
            return
        }

        logger.info { "🛑 Shutting down listener timeout processor..." }

        // Shutdown executor
        try {
            processingExecutor.shutdown()
            if (!processingExecutor.awaitTermination(gracePeriod, TimeUnit.MILLISECONDS)) {
                logger.warn { "⚠️ Forcing shutdown of timeout processing executor" }
                processingExecutor.shutdownNow()
            } else {
                logger.info { "✅ Timeout processing executor stopped gracefully" }
            }
        } catch (_: InterruptedException) {
            logger.error { "💥 Interrupted while shutting down timeout processing executor" }
            processingExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // Wait for active coroutines to complete
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    scope.coroutineContext.job.children.forEach { it.join() }
                }
                logger.info { "✅ All timeout processing tasks completed" }
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn { "⚠️ Graceful shutdown timed out with tasks still being processed" }
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during graceful shutdown" }
        } finally {
            scope.cancel()
            logger.info { "🏁 Listener timeout processor scope cancelled" }
        }
    }
}
