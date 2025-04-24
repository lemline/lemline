// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.debug
import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.warn
import jakarta.transaction.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.Logger

internal class OutboxProcessor<T : OutboxModel>(
    private val logger: Logger,
    private val repository: OutboxRepository<T>,
    private val processor: (T) -> Unit,
) {

    @VisibleForTesting
    internal fun calculateNextRetryDelay(attemptCount: Int, retryInitialDelayMillis: Int): Long {
        // Exponential backoff: initialDelay * 2^(attemptCount-1)
        // e.g., with initialDelay=1000ms (10s):
        // attempt 1: 1000ms * 2^0 = 1000ms +/- 20%
        // attempt 2: 1000ms * 2^1 = 2000ms +/- 20%
        // attempt 3: 1000ms * 2^2 = 4000ms +/- 20%
        val baseDelay = retryInitialDelayMillis * (1L shl (attemptCount - 1))

        // Add jitter of ±20%
        val jitterRange = baseDelay * 0.2 // 20% of base delay
        val jitter = (Math.random() - 0.5) * 2 * jitterRange // Random value between -1 and 1, multiplied by range

        return (baseDelay + jitter).toLong().coerceAtLeast(100) // Ensure we never return less than .1 second (100ms)
    }

    /**
     * Processes messages in the outbox table in batches.
     * Each message is processed by the provided processor function.
     * If processing fails, the message is updated with the error and will be retried later.
     * If processing succeeds, the message status is updated to SENT.
     *
     * @param batchSize The number of messages to process in each batch.
     * @param retryMaxAttempts The maximum number of retry attempts for each message.
     * @param retryInitialDelaySeconds The initial delay in seconds for retrying failed messages.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun process(batchSize: Int, retryMaxAttempts: Int, retryInitialDelaySeconds: Int) {
        try {
            var totalProcessed = 0
            var batchNumber = 0

            while (true) {
                // Find and lock messages ready to process
                val messages = repository.findAndLockReadyToProcess(batchSize, retryMaxAttempts)

                if (messages.isEmpty()) break

                batchNumber++
                logger.debug { "Processing batch $batchNumber with ${messages.size} messages" }

                for (message in messages) {
                    try {
                        // Process the message
                        processor(message)

                        // Update status to SENT in the same transaction
                        message.status = OutBoxStatus.SENT
                        logger.debug { "Successfully processed message ${message.id}" }
                        totalProcessed++
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to process message ${message.id}: ${e.message}" }
                        // Update status to PENDING and increment attempt count in the same transaction
                        message.attemptCount++
                        message.lastError = e.message ?: "Unknown error"

                        if (message.attemptCount >= retryMaxAttempts) {
                            message.status = OutBoxStatus.FAILED
                            logger.error { "Message ${message.id} has reached maximum retry attempts" }
                        } else {
                            // Calculate next retry time using exponential backoff
                            val nextDelay =
                                calculateNextRetryDelay(message.attemptCount, retryInitialDelaySeconds * 1000)
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
     * Cleans up old messages from the outbox table that are older than the specified number of days.
     * Messages are deleted in chunks to avoid locking the entire table.
     *
     * @param cleanupAfterDays The number of days after which messages should be cleaned up.
     * @param cleanupBatchSize The number of messages to delete in each batch.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun cleanup(cleanupAfterDays: Int, cleanupBatchSize: Int) {
        try {
            val cutoffDate = Instant.now().minusSeconds(cleanupAfterDays * 24 * 60 * 60L)
            var totalDeleted = 0
            var chunkNumber = 0

            while (true) {
                // Find and lock a chunk of messages for deletion
                val messagesToDelete = repository.findAndLockForDeletion(cutoffDate, cleanupBatchSize)

                if (messagesToDelete.isEmpty()) break

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
