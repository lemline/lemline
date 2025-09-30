// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

import com.lemline.common.logger.logger
import com.lemline.runner.config.LemlineConfiguration.TablesConfig.OutboxConfig
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.bases.OutboxModelBase
import com.lemline.runner.models.bases.runDelayedUntil
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.bases.OutboxRepositoryBase
import kotlin.jvm.optionals.getOrNull
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
internal class Outbox<T : OutboxModelBase>(
    private val outboxRepository: OutboxRepositoryBase<T>,
    private val failureRepository: FailureRepository,
    private val outboxConfig: OutboxConfig,
    private val relay: suspend (T) -> Unit,
) : Schedulable {

    override val logger = logger()
    override val every: Duration = outboxConfig.every
    override val gracePeriod: Duration = outboxConfig.gracePeriod
    override val isEnabled: Boolean = outboxConfig.enabled().getOrNull() != false

    override suspend fun run() = try {
        val batchSize: Int = outboxConfig.batchSize
        val maxAttempts: Int = outboxConfig.maxAttempts
        val initialDelay: Duration = outboxConfig.initialDelay

        var totalToProcess = 0
        var totalProcessed = 0
        var batchNumber = 0

        do {
            batchNumber++
            var toProcess = 0
            // Find and process messages in the same transaction
            outboxRepository.withTransaction { connection ->
                // Find and lock messages ready to process
                val entities = outboxRepository.findEntitiesToSend(maxAttempts, batchSize, connection)
                toProcess = entities.size

                if (toProcess > 0) {
                    totalToProcess += toProcess
                    val processed = process(entities, maxAttempts, initialDelay)
                    outboxRepository.update(entities, connection)
                    totalProcessed += processed

                    // Insert new failures into the FAILURE_TABLE within the same transaction
                    // This can be undone by retrying the outbox
                    val failures = entities
                        .filter { it.runStatus == RunStatus.FAILED }
                        .map { FailureModel.Companion.from(it) }
                    failureRepository.insert(failures, connection)
                }
            }
        } while (toProcess >= batchSize)

        logger.logBatches(totalProcessed, totalToProcess, batchNumber, "processed")
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
        outboxEntity.runAttemptCount++
        outboxEntity.runStatus = RunStatus.DONE
        relay(outboxEntity)
        true // <- return true (success)
    } catch (e: Exception) {
        logger.info(e) { "Failed to process outbox entity for workflow ${outboxEntity.workflowId}" }
        outboxEntity.runLastErrorClass = e::class.qualifiedName
        outboxEntity.runLastErrorMessage = e.message
        outboxEntity.runLastErrorStackTrace = e.stackTraceToString()

        if (outboxEntity.runAttemptCount >= maxAttempts) {
            outboxEntity.runStatus = RunStatus.FAILED
            logger.error { "Message ${outboxEntity.workflowId} has reached maximum retry attempts" }
        } else {
            outboxEntity.runStatus = RunStatus.PENDING
            val nextDelay = calculateNextAttemptDelay(outboxEntity.runAttemptCount, initialDelay)
            outboxEntity.runDelayedUntil = Clock.System.now() + nextDelay
            logger.debug { "Message ${outboxEntity.workflowId} will be retried in ${nextDelay}ms (attempt ${outboxEntity.runAttemptCount})" }
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
}
