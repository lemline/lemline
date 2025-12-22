// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.WithOutbox
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.with.WithCrudRepository
import com.lemline.runner.repositories.with.WithOutboxRepository
import com.lemline.runner.scheduled.AbstractScheduledTask
import jakarta.annotation.PostConstruct
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.annotations.VisibleForTesting

/**
 * AbstractOutbox provides base functionality for outbox pattern implementations.
 *
 * This class handles outbox processing - sending pending messages from the database.
 * Cleanup of old completed/failed messages is handled separately by cleaner classes
 * that extend [com.lemline.runner.cleaner.AbstractCleaner].
 *
 * The class uses a scheduled approach with configurable intervals for processing.
 * It ensures thread safety by using SKIP concurrent execution strategy, preventing
 * multiple instances of the same operation from running simultaneously.
 *
 * The processor implements the outbox pattern to ensure reliable message delivery by:
 * - Storing messages in a database before attempting to send them
 * - Processing messages in batches with configurable sizes
 * - Implementing retry logic with exponential backoff
 * - Setting `cleanupAfter = now + retention` when marking as completed
 *
 * @param T Type of the message entity (must implement WithOutbox interface)
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractOutbox<T : WithOutbox> : AbstractScheduledTask() {

    protected abstract val failureRepository: FailureRepository
    protected abstract val outboxRepository: WithOutboxRepository<T>
    protected abstract val crudRepository: WithCrudRepository<T>
    protected abstract val instanceEmitter: WorkflowCommandEmitter
    protected abstract val databaseManager: DatabaseManager

    protected abstract val outboxConf: LemlineConfiguration.OutboxProcessingConfig?
    protected abstract val cleanerConf: LemlineConfiguration.OutboxCleanupConfig

    override val taskName: String get() = "Outbox"
    override val interval: Duration get() = outboxConf?.every ?: Duration.INFINITE

    private val outboxProcessing = AtomicBoolean(false)

    /**
     * Process an outbox entity by transforming and sending it.
     * Subclasses MUST override this to transform Event → Command before sending.
     */
    protected abstract suspend fun process(entity: T)


    @PostConstruct
    override fun init() {
        if (!enabled) {
            logger.debug { "Outbox disabled by config" }
            return
        }

        // Schedule outbox processing
        scheduleTask()
    }

    /**
     * Safely executes the outbox task while ensuring that no concurrent executions occur.
     * This method uses an `AtomicBoolean` to prevent overlapping executions.
     */
    override suspend fun doWork() {
        if (isShuttingDown.get()) {
            logger.debug { "Skipping outbox processing: shutdown in progress" }
            return
        }

        if (!outboxProcessing.compareAndSet(false, true)) {
            logger.warn { "Skipping scheduled outbox processing: previous execution still running" }
            return
        }

        try {
            processEntities(
                batchSize = outboxConf!!.batchSize,
                maxAttempts = outboxConf!!.maxAttempts,
                initialDelay = outboxConf!!.initialDelay,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error during outbox processing" }
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
    open suspend fun processEntities(batchSize: Int, maxAttempts: Int, initialDelay: Duration) = try {
        var totalToProcess = 0
        var totalProcessed = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toProcess = 0
            // Find and process messages in the same transaction
            databaseManager.withTransaction { conn ->
                // Find and lock messages ready to process
                val messages = outboxRepository.findEntitiesToProcess(maxAttempts, batchSize, conn)
                toProcess = messages.size

                if (toProcess > 0) {
                    totalToProcess += toProcess
                    processBatch(messages, maxAttempts, initialDelay)
                    val processed = crudRepository.update(messages, conn)
                    totalProcessed += processed
                }
            }
        } while (toProcess >= batchSize)

        logBatches(totalProcessed, totalToProcess, batchNumber, "processed")
    } catch (e: Exception) {
        logger.error(e) { "Error during scheduled outbox processing" }
        // Don't throw the exception to prevent scheduler from stopping
        // The next scheduled run will try again
    }

    /**
     * Processes a list of entities concurrently on separate coroutines for improved performance.
     * Subclasses can override to pre-load related data before processing (e.g., batch-fetch
     * parent entities to avoid N+1 queries), then call [processEntitiesWith] with a custom processor.
     */
    protected open suspend fun processBatch(
        entities: List<T>,
        maxAttempts: Int,
        initialDelay: Duration
    ): Int = processEntitiesWith(entities, maxAttempts, initialDelay) { process(it) }

    /**
     * Processes entities concurrently using the provided processor function.
     * Use this when you need to pass pre-loaded data (e.g., a local cache) to the processor.
     *
     * Example usage in subclass:
     * ```
     * override suspend fun processBatch(entities: List<T>, maxAttempts: Int, initialDelay: Duration): Int {
     *     val cache = repository.findByIds(entities.map { it.parentId }.distinct())
     *     return processEntitiesWith(entities, maxAttempts, initialDelay) { entity ->
     *         val parent = cache[entity.parentId] ?: error("Not found")
     *         // process with parent...
     *     }
     * }
     * ```
     */
    protected suspend fun processEntitiesWith(
        entities: List<T>,
        maxAttempts: Int,
        initialDelay: Duration,
        processor: suspend (T) -> Unit
    ): Int = coroutineScope {
        entities.map { entity ->
            async { processEntityWith(entity, maxAttempts, initialDelay, processor) }
        }
    }.awaitAll().count { it }

    /**
     * Processes an entity using the provided processor function with retry handling.
     */
    private suspend fun processEntityWith(
        entity: T,
        maxAttempts: Int,
        initialDelay: Duration,
        processor: suspend (T) -> Unit
    ): Boolean = try {
        entity.outboxAttemptCount++
        processor(entity)
        // Mark as completed on success and schedule for cleanup
        entity.outboxCompletedAt = Clock.System.now()
        true // <- return true (success)
    } catch (e: Exception) {
        logger.info(e) { "Failed to process $entity" }
        entity.outboxErrorClass = e::class.qualifiedName
        entity.outboxErrorMessage = e.message
        entity.outboxErrorStackTrace = e.stackTraceToString()

        if (entity.outboxAttemptCount >= maxAttempts) {
            // Mark as permanently failed and schedule for cleanup
            val now = Clock.System.now()
            entity.outboxFailedAt = now
            logger.error { "Reached maximum retry attempts, marking as failed: $entity" }
        } else {
            // Schedule for retry with exponential backoff
            val nextDelay = calculateNextAttemptDelay(entity.outboxAttemptCount, initialDelay)
            entity.outboxDelayedUntil = Clock.System.now() + nextDelay
            logger.debug { "Failing processing outbox, retrying in ${nextDelay}ms (attempt ${entity.outboxAttemptCount}): $entity" }
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
                failed == 0 -> logger.debug { "All ${total.entities()} $action successfully (over ${batchNumber.batches()})" }
                else -> logger.debug { "${success.entities()} $action successfully and ${failed.entities()} failed (over ${batchNumber.batches()})" }
            }
        }
    }

    private fun Int.entities(): String = this.toString() + " entity" + if (this <= 1) "" else " entities"
    private fun Int.batches(): String = this.toString() + " batch" + if (this <= 1) "" else "es"
}
