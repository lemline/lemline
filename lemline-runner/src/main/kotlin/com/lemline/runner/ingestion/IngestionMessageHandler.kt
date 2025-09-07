// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.repositories.FAILURE_TABLE
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.PARENT_TABLE
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RETRY_TABLE
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.SCHEDULE_TABLE
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WAIT_TABLE
import com.lemline.runner.repositories.WaitRepository
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@ExperimentalTime
@ApplicationScoped
@ExperimentalSerializationApi
internal class IngestionMessageHandler(
    private val parentRepository: ParentRepository,
    private val retryRepository: RetryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val waitRepository: WaitRepository,
    private val failureRepository: FailureRepository,
    override val metrics: IngestionMessageSubscriberMetrics,
) : MessageHandler<IngestionMessage> {

    override var logger = logger()

    val maxAttempts: Int = 6
    val totalBudgetMs: Long = 50_000
    val singleAttemptTimeoutMs: Long = 10_000

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: IngestionMessage? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    /**
     * Deserializes the message payload. Returns the IngestionMessage
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun Message<String>.deserialize(): IngestionMessage = try {
        IngestionMessage.fromJsonString(payload)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
        throw CompensationException(DESERIALIZATION_FAILURE) { deserializationFailed(e) }
    }

    /**
     * Handles the lifecycle of an incoming ingestion message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    @Throws(CompensationException::class)
    override suspend fun IngestionMessage.handle(): IngestionMessage? {
        try {
            when (this) {
                is ParentIngestionMessage -> save()
                is RetryIngestionMessage -> save()
                is ScheduleIngestionMessage -> save()
                is WaitIngestionMessage -> save()
                is FailureIngestionMessage -> save()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error when saving message: $this" }
            throw CompensationException(getFailureReason(e)) {
                TODO()
            }
        }
        return null
    }

    private suspend fun ParentIngestionMessage.save() {
        retry(
            label = "Saving in $PARENT_TABLE table",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
        ) {
            withTimeout(singleAttemptTimeoutMs) {
                parentRepository.insert(model)
            }
        }
    }

    private suspend fun RetryIngestionMessage.save() {
        retry(
            label = "Saving in $RETRY_TABLE table",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
        ) {
            withTimeout(singleAttemptTimeoutMs) {
                retryRepository.insert(model)
            }
        }
    }

    private suspend fun ScheduleIngestionMessage.save() {
        retry(
            label = "Saving in $SCHEDULE_TABLE table",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
        ) {
            withTimeout(singleAttemptTimeoutMs) {
                scheduleRepository.insert(model)
            }
        }
    }

    private suspend fun WaitIngestionMessage.save() {
        retry(
            label = "Saving in $WAIT_TABLE table",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
        ) {
            withTimeout(singleAttemptTimeoutMs) {
                waitRepository.insert(model)
            }
        }
    }

    private suspend fun FailureIngestionMessage.save() {
        retry(
            label = "Saving in $FAILURE_TABLE table",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
        ) {
            withTimeout(singleAttemptTimeoutMs) {
                failureRepository.insert(model)
            }
        }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = FailureModel.from(
            id = IDV7.random(),
            payload = payload,
            reason = DESERIALIZATION_FAILURE,
            error = cause
        )
        failureRepository.insert(failure)
    }

    override suspend fun IngestionMessage.emit() {
        error("This method should not be called")
    }
}
