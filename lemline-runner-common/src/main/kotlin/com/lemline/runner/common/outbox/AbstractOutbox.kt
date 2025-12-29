// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.outbox

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.models.WithOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import com.lemline.runner.common.scheduled.AbstractScheduledTask
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
 * that extend [com.lemline.runner.common.cleaner.AbstractCleaner].
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
abstract class AbstractOutbox<T : WithOutbox> : AbstractScheduledTask() {

    protected abstract val outboxRepository: WithOutboxRepository<T>
    protected abstract val crudRepository: WithCrudRepository<T>
    protected abstract val commandEmitter: CommandEmitter
    protected abstract val databaseConfig: DatabaseConfig

    protected abstract val outboxConfig: OutboxConfig?

    override val interval: Duration get() = outboxConfig?.every ?: Duration.INFINITE

    override val initialDelay: Duration by lazy {
        outboxConfig?.randomInitialDelay ?: Duration.ZERO
    }

    private val outboxProcessing = AtomicBoolean(false)

    /**
     * Process an outbox entity by transforming and sending it.
     * Subclasses MUST override this to transform Event → Command before sending.
     */
    protected abstract suspend fun process(entity: T)

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
                batchSize = outboxConfig!!.batchSize,
                maxAttempts = outboxConfig!!.maxAttempts,
                retryDelay = outboxConfig!!.retryDelay,
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
     * - Base delay is configurable via retryDelay
     * - Each retry doubles the previous delay
     * - Maximum retry attempts are configurable
     *
     * @param batchSize Maximum number of messages to process in one batch
     * @param maxAttempts Maximum number of attempts before giving up (>=1)
     * @param retryDelay Base delay for exponential backoff on retries
     */
    @VisibleForTesting
    open suspend fun processEntities(batchSize: Int, maxAttempts: Int, retryDelay: Duration) = try {
        var totalToProcess = 0
        var totalProcessed = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toProcess = 0
            // Find and process messages in the same transaction
            databaseConfig.withTransaction { conn ->
                // Find and lock messages ready to process
                val messages = outboxRepository.findEntitiesToProcess(maxAttempts, batchSize, conn)
                toProcess = messages.size

                if (toProcess > 0) {
                    totalToProcess += toProcess
                    processBatch(messages, maxAttempts, retryDelay)
                    val processed = crudRepository.update(messages, conn)
                    totalProcessed += processed
                }
            }
        } while (toProcess >= batchSize)

        logBatches(totalProcessed, totalToProcess, batchNumber)
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
        retryDelay: Duration
    ): Int = processEntitiesWith(entities, maxAttempts, retryDelay) { process(it) }

    /**
     * Processes entities concurrently using the provided processor function.
     * Use this when you need to pass pre-loaded data (e.g., a local cache) to the processor.
     *
     * Example usage in subclass:
     * ```
     * override suspend fun processBatch(entities: List<T>, maxAttempts: Int, retryDelay: Duration): Int {
     *     val cache = repository.findByIds(entities.map { it.parentId }.distinct())
     *     return processEntitiesWith(entities, maxAttempts, retryDelay) { entity ->
     *         val parent = cache[entity.parentId] ?: error("Not found")
     *         // process with parent...
     *     }
     * }
     * ```
     */
    protected suspend fun processEntitiesWith(
        entities: List<T>,
        maxAttempts: Int,
        retryDelay: Duration,
        processor: suspend (T) -> Unit
    ): Int = coroutineScope {
        entities.map { entity ->
            async { processEntityWith(entity, maxAttempts, retryDelay, processor) }
        }
    }.awaitAll().count { it }

    /**
     * Processes an entity using the provided processor function with retry handling.
     */
    private suspend fun processEntityWith(
        entity: T,
        maxAttempts: Int,
        retryDelay: Duration,
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
            val nextDelay = calculateNextAttemptDelay(entity.outboxAttemptCount, retryDelay)
            entity.outboxDelayedUntil = Clock.System.now() + nextDelay
            logger.debug { "Failing processing outbox, retrying in ${nextDelay}ms (attempt ${entity.outboxAttemptCount}): $entity" }
        }
        false // <- return false (failure)
    }

    @VisibleForTesting
    internal fun calculateNextAttemptDelay(attemptCount: Int, retryDelay: Duration): Duration {
        // Exponential backoff: retryDelay * 2^(attemptCount-1)
        // e.g., with retryDelay=10s:
        // attempt 1: 10s * 2^0 = 10s +/- 20%
        // attempt 2: 10s * 2^1 = 20s +/- 20%
        // attempt 3: 10s * 2^2 = 40s +/- 20%
        val baseDelay = retryDelay.inWholeMilliseconds * (1L shl (attemptCount - 1))

        // Add jitter of ±20%
        val jitterRange = baseDelay * 0.2 // 20% of base delay
        val jitter = (Math.random() - 0.5) * 2 * jitterRange // Random value between -1 and 1, multiplied by range

        // Ensure we never return less than .1 second (100ms)
        return (baseDelay + jitter).toLong().coerceAtLeast(100L).milliseconds
    }

    private fun logBatches(success: Int, total: Int, batchNumber: Int) {
        val failed = total - success
        when (total) {
            0 -> logger.trace { "No row to process for $jobName" }
            else -> when {
                failed == 0 -> logger.debug { "All ${total.entities()} processed successfully (over ${batchNumber.batches()})" }
                else -> logger.debug { "${success.entities()} processed successfully and ${failed.entities()} failed (over ${batchNumber.batches()})" }
            }
        }
    }

    private fun Int.entities(): String = this.toString() + " entity" + if (this <= 1) "" else " entities"
    private fun Int.batches(): String = this.toString() + " batch" + if (this <= 1) "" else "es"
}
