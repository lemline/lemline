// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.logger.Logger
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.OutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.OutboxRepository
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
 * OutboxProcessor is a generic processor for handling outbox pattern operations.
 * It provides a reusable implementation for processing and managing messages in an outbox table,
 *
 * The processor implements the outbox pattern to ensure reliable message delivery by:
 * 1. Storing messages in a database before attempting to send them
 * 2. Processing messages in batches with configurable sizes
 * 3. Implementing retry logic with exponential backoff
 * 4. Cleaning up successfully processed messages
 *
 * Key features:
 * - Thread-safe batch processing
 * - Configurable retry strategies
 * - Transactional message handling
 * - Automatic cleanup of processed messages
 * - Detailed logging and error tracking
 *
 * @param logger Logger instance for tracking operations
 * @param outboxRepository Repository for accessing the outbox table
 * @param relay Function that processes individual messages
 * @param T Type of the message entity (must implement OutboxModel interface)
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal class OutboxRelay<T : OutboxModel>(
    private val logger: Logger,
    private val outboxRepository: OutboxRepository<T>,
    private val failureRepository: FailureRepository,
    private val relay: suspend (T) -> Unit,
) {
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
    suspend fun process(batchSize: Int, maxAttempts: Int, initialDelay: Duration) = try {
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
                    val processed = process(messages, maxAttempts, initialDelay)
                    outboxRepository.update(messages, connection)
                    totalProcessed += processed

                    // Insert new failures into the FAILURE_TABLE within the same transaction
                    // This can be undone by retrying the outbox
                    val failures = messages
                        .filter { it.outBoxStatus == OutBoxStatus.FAILED }
                        .map { FailureModel.from(it) }
                    failureRepository.insert(failures, connection)
                }
            }
        } while (toProcess >= batchSize)

        logBatches(totalProcessed, totalToProcess, batchNumber, "processed")
    } catch (e: Exception) {
        logger.error(e) { "💥Error during scheduled outbox processing" }
        // Don't throw the exception to prevent scheduler from stopping
        // The next scheduled run will try again
    }

    /**
     * Processes a list of entities concurrently on separate coroutines for improved performance.
     */
    private suspend fun process(
        entities: List<T>,
        maxAttempts: Int,
        initialDelay: Duration
    ): Int = coroutineScope {
        entities.map {
            async { processOutboxEntity(it, maxAttempts, initialDelay) }
        }
    }.awaitAll().count { it }

    /**
     * Processes a given message with retry handling, exponential backoff, and status update.
     *
     * The method increments the message's attempt count, processes the message,
     * and updates its status accordingly. If the processing fails, it implements
     * retries with a delayed schedule based on exponential backoff. Once the maximum
     * attempts have been reached, the message's status is set to FAILED.
     */
    private suspend fun processOutboxEntity(outboxEntity: T, maxAttempts: Int, initialDelay: Duration): Boolean = try {
        outboxEntity.outboxAttemptCount++
        outboxEntity.outBoxStatus = OutBoxStatus.SENT
        relay(outboxEntity)
        true // <- return true (success)
    } catch (e: Exception) {
        logger.info(e) { "Failed to process outbox entity for workflow ${outboxEntity.instanceMessage.workflowId}" }
        outboxEntity.outboxErrorClass = e::class.qualifiedName
        outboxEntity.outboxErrorMessage = e.message
        outboxEntity.outboxErrorStackTrace = e.stackTraceToString()

        if (outboxEntity.outboxAttemptCount >= maxAttempts) {
            outboxEntity.outBoxStatus = OutBoxStatus.FAILED
            logger.error { "Message ${outboxEntity.instanceMessage.workflowId} has reached maximum retry attempts" }
        } else {
            outboxEntity.outBoxStatus = OutBoxStatus.PENDING
            val nextDelay = calculateNextAttemptDelay(outboxEntity.outboxAttemptCount, initialDelay)
            outboxEntity.outboxDelayedUntil = Clock.System.now() + nextDelay
            logger.debug { "Message ${outboxEntity.instanceMessage.workflowId} will be retried in ${nextDelay}ms (attempt ${outboxEntity.outboxAttemptCount})" }
        }
        false // <- return false (failure)
    }


    /**
     * Cleans up old sent messages from the outbox table.
     * This method helps prevent database bloat by removing messages that:
     * 1. Have been successfully processed (status = SENT)
     * 2. Are older than the specified retention period
     *
     * The cleanup is performed in batches to:
     * - Prevent long-running transactions
     * - Avoid database locks
     * - Maintain system performance
     *
     * @param afterDelay Delay after which sent messages should be deleted
     * @param batchSize Maximum number of messages to delete in one batch
     */
    suspend fun cleanup(afterDelay: Duration, batchSize: Int) = try {

        val cutoffDate = Clock.System.now() - afterDelay

        var totalToDelete = 0
        var totalDeleted = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toDelete = 0
            // Find and delete messages in the same transaction
            outboxRepository.withTransaction { connection ->
                val messages = outboxRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)
                toDelete = messages.size

                if (toDelete > 0) {
                    totalToDelete += toDelete
                    val deleted = outboxRepository.delete(messages, connection)
                    totalDeleted += deleted
                }
            }
        } while (toDelete >= batchSize)

        logBatches(totalDeleted, totalToDelete, batchNumber, "deleted")
    } catch (e: Exception) {
        logger.error(e) { "💥 Error during scheduled outbox cleanup" }
        // Don't throw the exception to prevent scheduler from stopping
        // The next scheduled run will try again
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

    private fun Int.messages(): String = this.toString() + " message" + if (this == 1) "" else "s"
    private fun Int.batches(): String = this.toString() + " batch" + if (this == 1) "" else "es"
}
