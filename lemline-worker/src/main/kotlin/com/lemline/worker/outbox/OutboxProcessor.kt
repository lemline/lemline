// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.warn
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.Logger

/**
 * OutboxProcessor is a generic processor for handling outbox pattern operations.
 * It provides a reusable implementation for processing and managing messages in an outbox table,
 * supporting both wait and retry scenarios.
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
 * @param repository Repository for accessing the outbox table
 * @param processor Function that processes individual messages
 * @param T Type of the message entity (must implement OutboxModel interface)
 */
internal class OutboxProcessor<T : OutboxModel>(
    private val logger: Logger,
    private val repository: OutboxRepository<T>,
    private val processor: (T) -> Unit,
) {

    @VisibleForTesting
    internal fun calculateNextRetryDelay(attemptCount: Int, initialDelay: Duration): Long {
        // Exponential backoff: initialDelay * 2^(attemptCount-1)
        // e.g., with initialDelay=1000ms (10s):
        // attempt 1: 1000ms * 2^0 = 1000ms +/- 20%
        // attempt 2: 1000ms * 2^1 = 2000ms +/- 20%
        // attempt 3: 1000ms * 2^2 = 4000ms +/- 20%
        val baseDelay = initialDelay.toMillis() * (1L shl (attemptCount - 1))

        // Add jitter of ±20%
        val jitterRange = baseDelay * 0.2 // 20% of base delay
        val jitter = (Math.random() - 0.5) * 2 * jitterRange // Random value between -1 and 1, multiplied by range

        return (baseDelay + jitter).toLong().coerceAtLeast(100) // Ensure we never return less than .1 second (100ms)
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
     * The method uses exponential backoff for retries:
     * - Initial delay is configurable
     * - Each retry doubles the previous delay
     * - Maximum retry attempts are configurable
     *
     * Transaction Type: REQUIRES_NEW
     * - Creates a new transaction for each batch of messages
     * - Ensures that message processing is isolated from any existing transaction
     * - If the processor fails, only the current batch is rolled back
     * - Prevents long-running transactions that could block other operations
     * - Allows for independent retry of failed messages
     *
     * @param batchSize Maximum number of messages to process in one batch
     * @param maxAttempts Maximum number of attempts before giving up (>=1)
     * @param initialDelay Initial delay in seconds before first retry
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun process(batchSize: Int, maxAttempts: Int, initialDelay: Duration) {
        try {
            var totalProcessed = 0
            var batchNumber = 0
            var consecutiveEmptyBatches = 0
            val maxConsecutiveEmptyBatches = 3 // Prevent infinite loops

            while (consecutiveEmptyBatches < maxConsecutiveEmptyBatches) {
                // Find and lock messages ready to process
                val messages = repository.findMessagesToProcess(batchSize, maxAttempts)

                if (messages.isEmpty()) {
                    consecutiveEmptyBatches++
                    continue
                }

                consecutiveEmptyBatches = 0
                batchNumber++
                logger.debug { "Processing batch $batchNumber with ${messages.size} messages" }

                for (message in messages) {
                    try {
                        // Increment attempt count
                        message.attemptCount++
                        // Process the message
                        processor(message)

                        // Update status to SENT in the same transaction
                        message.status = OutBoxStatus.SENT
                        logger.debug { "Successfully processed message ${message.id}" }
                        totalProcessed++
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to process message ${message.id}: ${e.message}" }
                        message.lastError = e.message ?: "Unknown error"

                        if (message.attemptCount >= maxAttempts) {
                            message.status = OutBoxStatus.FAILED
                            logger.error { "Message ${message.id} has reached maximum retry attempts" }
                        } else {
                            // Calculate next retry time using exponential backoff
                            val nextDelay =
                                calculateNextRetryDelay(message.attemptCount, initialDelay)
                            message.delayedUntil = Instant.now().plus(nextDelay, ChronoUnit.MILLIS)
                            logger.debug {
                                "Message ${message.id} will be retried in ${nextDelay}ms (attempt ${message.attemptCount})"
                            }
                        }
                    }
                }
            }

            if (totalProcessed > 0) {
                logger.info { "Completed processing $totalProcessed messages in $batchNumber batches" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing delayed messages: ${e.message}" }
            // Don't throw the exception to prevent scheduler from stopping
            // The next scheduled run will try again
        }
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
     * Transaction Type: REQUIRES_NEW
     * - Creates a new transaction for each cleanup batch
     * - Ensures cleanup operations are isolated from other transactions
     * - If cleanup fails, only the current batch is rolled back
     * - Prevents long-running transactions during cleanup
     * - Allows for independent retry of failed cleanup operations
     *
     * @param afterDelay Delay after which sent messages should be deleted
     * @param batchSize Maximum number of messages to delete in one batch
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun cleanup(afterDelay: Duration, batchSize: Int) {
        try {
            val cutoffDate = Instant.now().minusMillis(afterDelay.toMillis())
            var totalDeleted = 0
            var chunkNumber = 0
            var consecutiveEmptyChunks = 0
            val maxConsecutiveEmptyChunks = 3 // Prevent infinite loops

            while (consecutiveEmptyChunks < maxConsecutiveEmptyChunks) {
                // Find and lock a chunk of messages for deletion
                val messagesToDelete = repository.findMessagesToDelete(cutoffDate, batchSize)

                if (messagesToDelete.isEmpty()) {
                    consecutiveEmptyChunks++
                    continue
                }

                consecutiveEmptyChunks = 0
                chunkNumber++
                val chunkDeleted = messagesToDelete.size

                // Delete the chunk
                messagesToDelete.forEach { repository.delete(it) }
                totalDeleted += chunkDeleted

                logger.info { "Cleaned up chunk $chunkNumber: $chunkDeleted messages (total: $totalDeleted)" }
            }

            if (totalDeleted > 0) {
                logger.info {
                    "Completed cleanup of $totalDeleted messages in $chunkNumber chunks (older than $cutoffDate)"
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error during cleanup of delayed messages: ${e.message}" }
            // Don't throw the exception to prevent scheduler from stopping
            // The next scheduled run will try again
        }
    }
}
