// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

import com.lemline.common.logger
import com.lemline.worker.config.LemlineConfiguration
import com.lemline.worker.config.WaitConfig
import com.lemline.worker.messaging.WORKFLOW_OUT
import com.lemline.worker.repositories.WaitRepository
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter

/**
 * WaitOutbox is responsible for processing and managing wait messages in the system.
 * It handles two main operations:
 * 1. Processing pending wait messages and sending them to the workflow output channel
 * 2. Cleaning up old sent messages to prevent database bloat
 *
 * The class uses a scheduled approach with configurable intervals for both operations.
 * It ensures thread safety by using SKIP concurrent execution strategy, preventing
 * multiple instances of the same operation from running simultaneously.
 *
 * Configuration is managed through LemlineConfiguration, allowing for flexible tuning of:
 * - Processing batch size
 * - Maximum retry attempts
 * - Initial delay between retries
 * - Cleanup retention period
 *
 * @see OutboxProcessor for the core message processing logic
 * @see WaitConfig for configuration details
 */
@ApplicationScoped
internal class WaitOutbox @Inject constructor(
    repository: WaitRepository,
    lemlineConfig: LemlineConfiguration,
    @Channel(WORKFLOW_OUT) emitter: Emitter<String>,
) {
    private val logger = logger()

    private val waitConfig: WaitConfig = lemlineConfig.wait()

    internal val outboxProcessor = OutboxProcessor(
        logger = logger,
        repository = repository,
        processor = { waitMessage -> emitter.send(waitMessage.message) },
    )

    /**
     * Processes pending wait messages from the outbox table.
     * This method is scheduled to run at configurable intervals.
     *
     * For each batch of messages:
     * 1. Retrieves messages that are ready to process (status = PENDING)
     * 2. Attempts to send each message using the emitter
     * 3. Updates message status to SENT on success
     * 4. Handles retries on failure with exponential backoff
     *
     * The operation is transactional and thread-safe, ensuring that:
     * - Messages are processed exactly once
     * - Failed messages are properly tracked and retried
     * - Concurrent processing is prevented
     */
    @Scheduled(every = "{lemline.wait.outbox.every}", concurrentExecution = SKIP)
    fun outbox() {
        val outboxConf = waitConfig.outbox()
        outboxProcessor.process(
            outboxConf.batchSize(),
            outboxConf.maxAttempts(),
            outboxConf.initialDelay().toSeconds().toInt(),
        )
    }

    /**
     * Cleans up old sent messages from the outbox table.
     * This method is scheduled to run at configurable intervals.
     *
     * For each batch of messages:
     * 1. Identifies messages that are:
     *    - Marked as SENT
     *    - Older than the configured retention period
     * 2. Deletes these messages in batches to prevent database locks
     *
     * The operation is transactional and thread-safe, ensuring that:
     * - Only sent messages are deleted
     * - Cleanup doesn't interfere with active message processing
     * - Database performance is maintained through batch processing
     */
    @Scheduled(every = "{lemline.wait.cleanup.every}", concurrentExecution = SKIP)
    fun cleanup() {
        val cleanupConf = waitConfig.cleanup()
        outboxProcessor.cleanup(
            cleanupConf.after().toDays().toInt(),
            cleanupConf.batchSize(),
        )
    }
}
