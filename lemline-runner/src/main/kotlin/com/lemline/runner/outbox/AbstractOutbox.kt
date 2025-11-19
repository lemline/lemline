// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.cleaner.AbstractCleaner
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
import jakarta.annotation.PostConstruct
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.annotations.VisibleForTesting

/**
 * AbstractRelay provides base functionality for outbox pattern implementations.
 * It extends AbstractCleaner to inherit cleanup scheduling and shutdown logic,
 * and adds processing functionality for pending messages.
 *
 * It handles scheduling and execution of processing pending messages and sending
 * them to the workflow output channel. Cleanup of old sent messages is handled
 * by the parent AbstractCleaner class.
 *
 * The class uses a scheduled approach with configurable intervals for processing.
 * It ensures thread safety by using SKIP concurrent execution strategy, preventing
 * multiple instances of the same operation from running simultaneously.
 *
 * The processor implements the outbox pattern to ensure reliable message delivery by:
 * - Storing messages in a database before attempting to send them
 * - Processing messages in batches with configurable sizes
 * - Implementing retry logic with exponential backoff
 *
 * @param T Type of the message entity (must implement OutboxModel interface)
 * @see AbstractCleaner for the cleanup infrastructure
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractOutbox<T : OutboxModel> : AbstractCleaner<T>() {

    protected abstract val failureRepository: FailureRepository
    protected abstract val outboxRepository: OutboxRepository<T>
    override val cleanerRepository: OutboxRepository<T> get() = outboxRepository
    protected abstract val instanceEmitter: WorkflowCommandEmitter

    protected abstract val outboxConf: LemlineConfiguration.OutboxProcessingConfig?

    private val outboxProcessingExecutor = Executors.newSingleThreadScheduledExecutor()
    private val outboxProcessing = AtomicBoolean(false)

    /**
     * Process an outbox entity by transforming and sending it.
     * Subclasses MUST override this to transform Event → Command before sending.
     */
    protected abstract suspend fun process(entity: T)

    @PostConstruct
    override fun init() {
        if (!enabled) {
            logger.debug { "🚫 Relay disabled by config" }
            return
        }

        // Schedule outbox processing
        outboxConf?.every?.inWholeSeconds?.let { period ->
            outboxProcessingExecutor.scheduleAtFixedRate(
                { scope.launch { outbox() } },
                0,
                period,
                TimeUnit.SECONDS
            )
            logger.info { "⏱️ Relay processing scheduled every ${period}s" }
        }

        // Schedule cleanup (inherited from AbstractCleaner)
        scheduleCleanup()
    }

    /**
     * Shutdown additional executors (processing executor).
     * Cleanup executor is shut down by the parent class.
     */
    override fun shutdownExecutors() {
        shutdownExecutor(outboxProcessingExecutor, "processing")
        super.shutdownExecutors() // Shutdown cleanup executor
    }

    /**
     * Safely executes the outbox task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    private suspend fun outbox() {
        if (isShuttingDown.get()) {
            logger.debug { "⏹️ Skipping relay processing: shutdown in progress" }
            return
        }

        if (!outboxProcessing.compareAndSet(false, true)) {
            logger.warn { "⏭ Skipping scheduled relay processing: previous execution still running" }
            return
        }

        try {
            processEntities(
                batchSize = outboxConf!!.batchSize,
                maxAttempts = outboxConf!!.maxAttempts,
                initialDelay = outboxConf!!.initialDelay,
            )
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during relay processing" }
        } finally {
            outboxProcessing.set(false)
        }
    }

    /**
     * Processes messages from the outbox table in batches.
     * This method implements the core outbox pattern logic:
     *
     * 1. Retrieves a batch of pending messages
     * 2. For each message:
     *    - Attempts to process it using the provided processor
     *    - On success, marks the message as sent
     *    - On failure, implements retry logic with exponential backoff
     * 3. Handles concurrent processing safely
     *
     * It's crucial to run this method within a transaction to ensure data consistency.
     * Without a transaction, another runner could process the same messages concurrently
     * while this one is still handling the results of the `findMessagesToProcess` query.
     *
     * The method uses exponential backoff for retries:
     * - Initial delay is configurable
     * - Each retry doubles the previous delay
     * - Maximum retry attempts are configurable
     *
     * @param batchSize Maximum number of messages to process in one batch
     * @param maxAttempts Maximum number of attempts before giving up (>=1)
     * @param initialDelay Initial delay in seconds before first retry
     */
    @VisibleForTesting
    internal suspend fun processEntities(batchSize: Int, maxAttempts: Int, initialDelay: Duration) = try {
        var totalToProcess = 0
        var totalProcessed = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toProcess = 0
            // Find and process messages in the same transaction
            outboxRepository.withTransaction { connection ->
                // Find and lock messages ready to process
                val messages = outboxRepository.findEntitiesToProcess(maxAttempts, batchSize, connection)
                toProcess = messages.size

                if (toProcess > 0) {
                    totalToProcess += toProcess
                    val processed = processBatch(messages, maxAttempts, initialDelay)
                    outboxRepository.update(messages, connection)
                    totalProcessed += processed
                }
            }
        } while (toProcess >= batchSize)

        logBatches(totalProcessed, totalToProcess, batchNumber, "processed")
    } catch (e: Exception) {
        logger.error(e) { "💥Error during scheduled relay processing" }
        // Don't throw the exception to prevent scheduler from stopping
        // The next scheduled run will try again
    }

    /**
     * Processes a list of entities concurrently on separate coroutines for improved performance.
     */
    private suspend fun processBatch(
        entities: List<T>,
        maxAttempts: Int,
        initialDelay: Duration
    ): Int = coroutineScope {
        entities.map {
            async { processEntity(it, maxAttempts, initialDelay) }
        }
    }.awaitAll().count { it }

    /**
     * Processes a given message with retry handling, exponential backoff, and timestamp updates.
     *
     * The method increments the message's attempt count, processes the message,
     * and updates timestamps accordingly. If the processing fails, it implements
     * retries with a delayed schedule based on exponential backoff. Once the maximum
     * attempts have been reached, outbox_failed_at is set.
     */
    private suspend fun processEntity(entity: T, maxAttempts: Int, initialDelay: Duration): Boolean = try {
        entity.outboxAttemptCount++
        process(entity)
        // Mark as completed on success
        entity.outboxCompletedAt = Clock.System.now()
        true // <- return true (success)
    } catch (e: Exception) {
        logger.info(e) { "Failed to process $entity" }
        entity.outboxErrorClass = e::class.qualifiedName
        entity.outboxErrorMessage = e.message
        entity.outboxErrorStackTrace = e.stackTraceToString()

        if (entity.outboxAttemptCount >= maxAttempts) {
            // Mark as permanently failed
            entity.outboxFailedAt = Clock.System.now()
            logger.error { "Message ${entity.instanceMessage.workflowId} has reached maximum retry attempts" }
        } else {
            // Schedule for retry with exponential backoff
            val nextDelay = calculateNextAttemptDelay(entity.outboxAttemptCount, initialDelay)
            entity.outboxDelayedUntil = Clock.System.now() + nextDelay
            logger.debug { "Message ${entity.instanceMessage.workflowId} will be retried in ${nextDelay}ms (attempt ${entity.outboxAttemptCount})" }
        }
        false // <- return false (failure)
    }

    @VisibleForTesting
    internal fun calculateNextAttemptDelay(attemptCount: Int, initialDelay: Duration): Duration {
        // Exponential backoff: initialDelay * 2^(attemptCount-1)
        // e.g., with initialDelay=1000ms (10s):
        // attempt 1: 1000ms * 2^0 = 1000ms +/- 20%
        // attempt 2: 1000ms * 2^1 = 2000ms +/- 20%
        // attempt 3: 1000ms * 2^2 = 4000ms +/- 20%
        val baseDelay = initialDelay.inWholeMilliseconds * (1L shl (attemptCount - 1))

        // Add jitter of ±20%
        val jitterRange = baseDelay * 0.2 // 20% of base delay
        val jitter = (Math.random() - 0.5) * 2 * jitterRange // Random value between -1 and 1, multiplied by range

        // Ensure we never return less than .1 second (100ms)
        return (baseDelay + jitter).toLong().coerceAtLeast(100L).milliseconds
    }

    private fun logBatches(success: Int, total: Int, batchNumber: Int, action: String) {
        val failed = total - success
        when (total) {
            0 -> logger.debug { "No message found to process" }
            else -> when {
                failed == 0 -> logger.debug { "All ${total.messages()} $action successfully (over ${batchNumber.batches()})" }
                else -> logger.debug { "${success.messages()} $action successfully and ${failed.messages()} failed (over ${batchNumber.batches()})" }
            }
        }
    }

    private fun Int.messages(): String = this.toString() + " message" + if (this <= 1) "" else "s"
    private fun Int.batches(): String = this.toString() + " batch" + if (this <= 1) "" else "es"
}
