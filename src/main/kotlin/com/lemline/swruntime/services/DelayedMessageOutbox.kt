package com.lemline.swruntime.services

import com.lemline.swruntime.*
import com.lemline.swruntime.json.Json
import com.lemline.swruntime.messaging.WorkflowMessage
import com.lemline.swruntime.models.DelayedMessage
import com.lemline.swruntime.models.DelayedMessage.MessageStatus.FAILED
import com.lemline.swruntime.models.DelayedMessage.MessageStatus.SENT
import com.lemline.swruntime.repositories.DelayedMessageRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.time.Instant
import java.time.temporal.ChronoUnit

@ApplicationScoped
class DelayedMessageOutbox(
    val delayedMessageRepository: DelayedMessageRepository,

    @Channel("workflow-execution-out")
    val emitter: Emitter<WorkflowMessage>,

    @ConfigProperty(name = "delayed.batch-size", defaultValue = "100")
    val batchSize: Int,

    @ConfigProperty(name = "delayed.retry-max-attempts", defaultValue = "5")
    val retryMaxAttempts: Int,

    @ConfigProperty(name = "delayed.retry-initial-delay-seconds", defaultValue = "10")
    val retryInitialDelaySeconds: Long,

    @ConfigProperty(name = "delayed.cleanup-after-days", defaultValue = "7")
    val cleanupAfterDays: Int,

    @ConfigProperty(name = "delayed.cleanup-batch-size", defaultValue = "500")
    val cleanupBatchSize: Int,
) {
    private val logger = logger()

    private fun processMessage(message: DelayedMessage) {
        println("MESSAGE = ${message.message}")
        val workflowMessage: WorkflowMessage = Json.fromJson(message.message)
        println(workflowMessage)
        // Send the message to the appropriate channel
        emitter.send(workflowMessage)
    }

    private fun calculateNextRetryDelay(attemptCount: Int): Long {
        // Exponential backoff: initialDelay * 2^attemptCount
        // e.g., with initialDelay=5s:
        // attempt 1: 10s
        // attempt 2: 20s
        // attempt 3: 40s
        return retryInitialDelaySeconds * (1L shl (attemptCount - 1))
    }

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    @Transactional
    fun processOutbox() {
        try {
            var totalProcessed = 0
            var batchNumber = 0

            while (true) {
                // Find and lock messages ready to process
                val messages = delayedMessageRepository.findAndLockReadyToProcess(batchSize, retryMaxAttempts)

                if (messages.isEmpty()) break

                batchNumber++
                logger.debug { "Processing batch $batchNumber with ${messages.size} messages" }

                for (message in messages) {
                    try {
                        // Process the message
                        processMessage(message)

                        // Update status to SENT in the same transaction
                        message.status = SENT
                        logger.debug { "Successfully processed message ${message.id}" }
                        totalProcessed++
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to process message ${message.id}: ${e.message}" }
                        // Update status to PENDING and increment attempt count in the same transaction
                        message.attemptCount++
                        message.lastError = e.message ?: "Unknown error"

                        if (message.attemptCount >= retryMaxAttempts) {
                            message.status = FAILED
                            logger.error { "Message ${message.id} has reached maximum retry attempts" }
                        } else {
                            // Calculate next retry time using exponential backoff
                            val nextDelay = calculateNextRetryDelay(message.attemptCount)
                            message.delayedUntil = Instant.now().plus(nextDelay, ChronoUnit.SECONDS)
                            logger.debug { "Message ${message.id} will be retried in ${nextDelay}s (attempt ${message.attemptCount})" }
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

    @Scheduled(every = "1h", concurrentExecution = SKIP)
    @Transactional
    fun cleanupOutbox() {
        try {
            val cutoffDate = Instant.now().minusSeconds(cleanupAfterDays * 24 * 60 * 60L)
            var totalDeleted = 0
            var chunkNumber = 0

            while (true) {
                // Find and lock a chunk of messages for deletion
                val messagesToDelete =
                    delayedMessageRepository.findAndLockForDeletion(cutoffDate, cleanupBatchSize)

                if (messagesToDelete.isEmpty()) break

                chunkNumber++
                val chunkDeleted = messagesToDelete.size

                // Delete the chunk
                messagesToDelete.forEach { it.delete() }
                totalDeleted += chunkDeleted

                logger.info { "Cleaned up chunk $chunkNumber: $chunkDeleted messages (total: $totalDeleted)" }
            }

            if (totalDeleted > 0) {
                logger.info { "Completed cleanup of $totalDeleted messages in $chunkNumber chunks (older than $cutoffDate)" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error during cleanup of delayed messages: ${e.message}" }
            // Don't throw the exception to prevent scheduler from stopping
            // The next scheduled run will try again
        }
    }
} 