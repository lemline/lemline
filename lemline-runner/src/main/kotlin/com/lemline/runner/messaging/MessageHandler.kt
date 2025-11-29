// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.Logger
import com.lemline.common.logger.withSuspendLoggingContext
import com.lemline.common.values.IDV7
import com.lemline.common.values.WithOptionalWorkflowInfo
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.healthcheck.FatalAckLiveness.livenessDownOnFailure
import com.lemline.runner.healthcheck.RetryReadiness.readinessDownDuringRetries
import io.quarkus.smallrye.reactivemessaging.ackSuspending
import io.quarkus.smallrye.reactivemessaging.nackSuspending
import kotlin.time.ExperimentalTime
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
internal interface MessageHandler<T> {

    suspend fun Message<String>.deserialize(): T

    suspend fun handle(current: T): T?

    /**
     * Serializes the message to a JSON string payload.
     * Can throw CompensationException for corruption/serialization errors.
     */
    suspend fun serialize(current: T, next: T): String

    /**
     * Emits the serialized payload to the message broker.
     * This is called with retry logic, so just perform the send operation.
     *
     * @param payload The serialized message payload
     * @param idempotentKey Optional deterministic ID for message deduplication.
     *                      If null, a random ID will be generated.
     */
    suspend fun emit(payload: String, idempotentKey: IDV7)

    /**
     * Derives an idempotent key for the given message.
     * Override to provide deterministic IDs based on message content.
     *
     * @param next The message to derive the key for
     * @return An IDV7 for deduplication, or null for random ID
     */
    fun deriveIdempotentKey(next: T): IDV7

    val logger: Logger

    val metrics: MessageSubscriberMetrics

    val onCompleteTest: (Message<String>, T?) -> Unit

    val onFailureTest: (Message<String>, Throwable?) -> Unit

    // Retrieve workflowInfo if present
    val T?.workflowInfo get() = (this as? WithOptionalWorkflowInfo)?.workflowInfo

    // Retrieve workflowId if present
    val T?.workflowId get() = (this as? InstanceMessage<*>)?.workflowState?.workflowId

    suspend fun handleMessage(message: Message<String>) {
        logger.debug { "Received: ${message.toLogString()}" }

        // --- Deserialization ---
        val msg: T = message.tryWithCompensation(null, null) {
            metrics.recordDeserializationDuration {
                message.deserialize()
            }
        }.getOrElse { onFailureTest(message, it); return } // <- tryWithCompensation handles ack if fails

        // --- Processing  ---
        message.tryWithCompensation(msg.workflowId, msg.workflowInfo) {
            val next: T? = metrics.recordProcessingDuration(msg.workflowInfo) {
                handle(msg)
            }
            val nextPayload: String? = next?.let {
                metrics.recordSerializationDuration(next.workflowInfo) {
                    serialize(msg, it)
                }
            }
            // Derive idempotent key and emit with it
            nextPayload?.let { payload ->
                emit(payload, deriveIdempotentKey(next))
            }

            onCompleteTest(message, next)

        }.getOrElse { onFailureTest(message, it); return } // <- tryWithCompensation handles ack if fails

        // Success Path - ACK the original message
        message.acknowledgeWithRetry(msg.workflowId, msg.workflowInfo)
    }

    /**
     * Executes a suspending processing [block] for this message and wraps it with
     * compensation-aware error handling and broker ACK/NACK semantics.
     *
     * Execution is done inside a logging context derived from [workflowInfo].
     *
     * Behavior:
     * - **Success path**: If [block] completes normally, its result is wrapped in
     *   [Result.success] and returned. The caller is responsible for any final ACK.
     * - **Compensation path**: If [block] throws a [CompensationException],
     *   the exception’s `run` callback is invoked to perform compensating work
     *   (e.g. persisting failure details). If compensation succeeds, the message is
     *   acknowledged via [acknowledgeWithRetry] and a failed [Result] containing the
     *   original [CompensationException] is returned.
     * - **Failure during compensation**: If executing the compensation callback or
     *   acknowledging the message throws, the error is logged, the message is
     *   negatively acknowledged via [negAcknowledgeWithRetry], and a failed [Result]
     *   with that error is returned.
     * - **Generic failure path**: If [block] throws any other [Exception], the error
     *   is logged, the message is negatively acknowledged via [negAcknowledgeWithRetry],
     *   and a failed [Result] is returned.
     *
     * This helper ensures that:
     * - Business logic can delegate failure handling via [CompensationException].
     * - ACK/NACK behavior is consistent and uses the configured retry policies.
     * - Callers only need to inspect the returned [Result] and do not have to manage
     *   compensation or broker semantics directly.
     *
     * @param workflowInfo Optional workflow context used for logging and metrics.
     * @param block The processing logic to execute for this message.
     * @return [Result.success] with the block result on success, or [Result.failure]
     *   with the thrown exception after the appropriate compensation / ACK / NACK
     *   behavior has been applied.
     */
    suspend fun <S> Message<String>.tryWithCompensation(
        workflowId: WorkflowId?,
        workflowInfo: WorkflowInfo?,
        block: suspend () -> S
    ): Result<S> = withSuspendLoggingContext(workflowId, workflowInfo) {
        try {
            Result.success(block())
        } catch (compensation: CompensationException) {
            try {
                compensation.run()
                acknowledgeWithRetry(workflowId, workflowInfo)
                Result.failure(compensation)
            } catch (e: Exception) {
                // Failure path
                logger.error(e) { "Failed to execute compensation for ${toLogString()}" }
                negAcknowledgeWithRetry(e, workflowInfo)
                Result.failure(e)
            }
        } catch (e: Exception) {
            // Failure path
            logger.error(e) { "Failed to process ${toLogString()}" }
            negAcknowledgeWithRetry(e, workflowInfo)
            Result.failure(e)
        }
    }

    /**
     * Acknowledges the current message with retry logic.
     *
     * If the acknowledgment fails, an exception is thrown to trigger a broker reconnection
     */
    suspend fun Message<String>.acknowledgeWithRetry(
        workflowId: WorkflowId?,
        workflowInfo: WorkflowInfo?,
    ) = try {
        ackWithRetry()
        logger.debug { "Message ACKed: ${toLogString()}" }
        metrics.ackCompleted(workflowInfo)
    } catch (e: Exception) {
        logger.error(e) { "Failed to ACK message: ${toLogString()}" }
        metrics.ackFailed(workflowInfo)
        throw e
    }

    /**
     * Acknowledges a message with retry logic, ensuring retries with exponential backoff if the acknowledgment fails.
     * The method implements local retries to keep acknowledgment and processing within the defined time and attempt limits.
     *
     * @param maxAttempts The maximum number of retry attempts for acknowledgment. Defaults to 6.
     * @param totalBudgetMs The total allowable time budget for acknowledgment retries, in milliseconds. Defaults to 60,000 ms.
     * @param singleAttemptTimeoutMs The timeout for a single acknowledgment attempt, in milliseconds. Defaults to 10,000 ms.
     */
    private suspend fun Message<*>.ackWithRetry(
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 6_000, // Keep local retry+processing under throttled.unprocessed-record-max-age.ms
        singleAttemptTimeoutMs: Long = 1_000
    ) = retry(
        logger = logger,
        label = "ACK",
        maxAttempts = maxAttempts,
        totalBudgetMs = totalBudgetMs,
        singleAttemptTimeoutMs = singleAttemptTimeoutMs,
        onRetry = { readinessDownDuringRetries.set(true) },
        onSuccess = { readinessDownDuringRetries.set(false) },
        onFailure = { _, _, _ -> livenessDownOnFailure.set(true) }
    ) {
        ackSuspending()
    }

    /**
     * Handles the process of negatively acknowledging a message with retry logic.
     * If the retry attempts fail, the message is quarantined locally, and an acknowledgment
     * is attempted to ensure the message is processed within the defined constraints.
     *
     * If quarantining locally or the acknowledgment fails, an exception is thrown to trigger a broker reconnection
     */
    suspend fun Message<String>.negAcknowledgeWithRetry(
        e: Exception,
        workflowInfo: WorkflowInfo?
    ) = try {
        nackWithRetry(e)
        logger.warn { "Message NACKed: ${toLogString()} - should be sent to the DLQ by brokers" }
        metrics.nackCompleted(workflowInfo)
    } catch (e: Exception) {
        logger.error(e) { "Failed to NACK message: ${toLogString()}" }
        metrics.nackFailed(workflowInfo)
        throw e
    }

    /**
     * Attempts to negatively acknowledge a message multiple times with retry logic.
     * This method supports configurable retry attempts, a total time budget for retries,
     * and a timeout for each individual attempt. The negative acknowledgment is performed
     * asynchronously, with detailed handling for failed attempts.
     *
     * @param cause The exception or error that caused the message to be negatively acknowledged.
     * @param maxAttempts The maximum number of retry attempts. Default is 6.
     * @param totalBudgetMs The total time budget in milliseconds for all retry attempts combined. Default is 60,000 ms.
     * @param singleAttemptTimeoutMs The timeout in milliseconds for each individual retry attempt. Default is 10,000 ms.
     */
    private suspend fun Message<*>.nackWithRetry(
        cause: Throwable,
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 6_000,
        singleAttemptTimeoutMs: Long = 1_000
    ) = retry(
        logger = logger,
        label = "NACK",
        maxAttempts = maxAttempts,
        totalBudgetMs = totalBudgetMs,
        singleAttemptTimeoutMs = singleAttemptTimeoutMs,
        onRetry = { readinessDownDuringRetries.set(true) },
        onSuccess = { readinessDownDuringRetries.set(false) },
        onFailure = { _, _, _ -> livenessDownOnFailure.set(true) }
    ) {
        nackSuspending(cause)
    }

    companion object {
        private const val UNKNOWN = ""
        val UNKNOWN_NAME = WorkflowName(UNKNOWN)
        val UNKNOWN_VERSION = WorkflowVersion(UNKNOWN)
    }
}

class CompensationException(val reason: String, val run: suspend () -> Unit) : RuntimeException()
