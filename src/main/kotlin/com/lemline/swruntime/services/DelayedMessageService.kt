package com.lemline.swruntime.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.swruntime.*
import com.lemline.swruntime.messaging.WorkflowMessage
import com.lemline.swruntime.models.DelayedMessage
import com.lemline.swruntime.repositories.DelayedMessageRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import java.time.Instant

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
@ApplicationScoped
class DelayedMessageService {
    private val logger = logger()

    @Inject
    private lateinit var delayedMessageRepository: DelayedMessageRepository

    @Inject
    @Channel("workflow-execution-out")
    private lateinit var emitter: Emitter<WorkflowMessage>

    @ConfigProperty(name = "delayed.max-attempts", defaultValue = "3")
    private lateinit var maxAttempts: Integer

    @ConfigProperty(name = "delayed.batch-size", defaultValue = "100")
    private lateinit var batchSize: Integer

    @ConfigProperty(name = "delayed.cleanup-after-days", defaultValue = "7")
    private lateinit var cleanupAfterDays: Integer

    @ConfigProperty(name = "delayed.cleanup-batch-size", defaultValue = "500")
    private lateinit var cleanupBatchSize: Integer

    private val objectMapper = ObjectMapper()

    @Transactional
    fun saveMessage(message: String, delayedUntil: Instant) {
        val delayedMessage = DelayedMessage().apply {
            this.message = message
            this.delayedUntil = delayedUntil
            this.status = DelayedMessage.MessageStatus.PENDING
        }
        delayedMessage.persist()
    }

    @Scheduled(every = "5s", concurrentExecution = SKIP)
    @Transactional
    fun processOutbox() {
        try {
            var totalProcessed = 0
            var batchNumber = 0

            while (true) {
                // Find and lock messages ready to process
                val messages =
                    delayedMessageRepository.findAndLockReadyToProcess(batchSize.toInt(), maxAttempts.toInt())

                if (messages.isEmpty()) break

                batchNumber++
                logger.debug { "Processing batch $batchNumber with ${messages.size} messages" }

                for (message in messages) {
                    try {
                        // Process the message
                        processMessage(message)

                        // Update status to SENT in the same transaction
                        message.status = DelayedMessage.MessageStatus.SENT
                        logger.debug { "Successfully processed message ${message.id}" }
                        totalProcessed++
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to process message ${message.id}: ${e.message}" }
                        // Update status to PENDING and increment attempt count in the same transaction
                        message.status = DelayedMessage.MessageStatus.PENDING
                        message.attemptCount++
                        message.lastError = e.message ?: "Unknown error"

                        if (message.attemptCount >= maxAttempts.toInt()) {
                            logger.error { "Message ${message.id} has reached maximum retry attempts" }
                            message.status = DelayedMessage.MessageStatus.FAILED
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
    fun cleanupOldMessages() {
        try {
            val cutoffDate = Instant.now().minusSeconds(cleanupAfterDays.toInt() * 24 * 60 * 60L)
            var totalDeleted = 0
            var chunkNumber = 0

            while (true) {
                // Find and lock a chunk of messages for deletion
                val messagesToDelete =
                    delayedMessageRepository.findAndLockForDeletion(cutoffDate, cleanupBatchSize.toInt())

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

    private fun processMessage(message: DelayedMessage) {
        // Send the message to the appropriate channel
        emitter.send(objectMapper.readValue(message.message, WorkflowMessage::class.java))
    }
} 