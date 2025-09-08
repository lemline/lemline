// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.Logger
import com.lemline.common.logger.withSuspendLoggingContext
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.healthcheck.FatalAckLiveness.livenessDownOnFatal
import com.lemline.runner.healthcheck.RetryReadiness.readinessDownDuringRetries
import com.lemline.runner.models.WithInstanceInfo
import io.quarkus.smallrye.reactivemessaging.ackSuspending
import io.quarkus.smallrye.reactivemessaging.nackSuspending
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.kafka.common.errors.RetriableException
import org.eclipse.microprofile.reactive.messaging.Message

@ExperimentalTime
internal interface MessageHandler<T : WithInstanceInfo> {

    suspend fun Message<String>.deserialize(): T

    suspend fun T.handle(): T?

    suspend fun T.emit()

    val logger: Logger

    val metrics: MessageSubscriberMetrics

    val onCompleteTest: (Message<String>, T?) -> Unit

    val onFailureTest: (Message<String>, Throwable?) -> Unit

    suspend fun handleMessage(message: Message<String>) {
        var next: T?
        var workflowId: WorkflowId? = null
        var workflowName: WorkflowName? = null
        var workflowVersion: WorkflowVersion? = null

        // --- Deserialization ---
        val msg: T = message.tryWithCompensation {
            logger.debug { "Received: ${message.toLogString()}" }
            metrics.recordDeserializationDuration {
                try {
                    message.deserialize().also {
                        // deserialisation succeeded
                        logger.debug { "Deserialized ${message.toLogString()}: $it" }
                        workflowId = it.workflowId
                        workflowName = it.workflowName
                        workflowVersion = it.workflowVersion
                        metrics.deserializationCompleted(workflowName, workflowVersion)
                    }
                } catch (e: Exception) {
                    metrics.deserializationFailed(e)
                    throw e
                }
            }
        }.getOrElse { return } // <- tryWithCompensation handles (neg)acknowledgment if the block fails

        // --- Processing ---
        message.tryWithCompensation(workflowId, workflowName, workflowVersion) {
            // Process et get next message
            next = metrics.recordProcessingDuration(workflowName, workflowVersion) {
                try {
                    msg.handle().also {
                        logger.debug { "Processed: ${message.toLogString()}" }
                        metrics.processingCompleted(workflowName, workflowVersion)
                    }
                } catch (e: Exception) {
                    val reason = if (e is CompensationException) e.reason else getFailureReason(e)
                    metrics.processingFailed(reason, workflowName, workflowVersion)
                    throw e
                }
            }
            // Serialize and emit the next message if any
            next?.emit()
        }.getOrElse { return } // <- tryWithCompensation handles (neg)acknowledgment if the block fails

        // Success Path
        message.acknowledgeWithRetry(workflowName, workflowVersion)
    }

    /**
     * Attempts to execute a suspending block within a log context.
     * If failing,
     * - the compensation actions of the CompensationException are executed, and the message is acknowledged.
     * - if failing again, the message is negatively acknowledged.
     *
     * @return The result of the block if successful, else null
     *
     * @throws Exception If an error occurs during (negative) acknowledgment
     */
    suspend fun <T> Message<String>.tryWithCompensation(
        workflowId: WorkflowId? = null,
        workflowName: WorkflowName? = null,
        workflowVersion: WorkflowVersion? = null,
        block: suspend () -> T
    ): Result<T> = withSuspendLoggingContext(workflowId, workflowName, workflowVersion) {
        try {
            Result.success(block())
        } catch (compensation: CompensationException) {
            try {
                compensation.run()
                acknowledgeWithRetry(workflowName, workflowVersion)
            } catch (e: Exception) {
                // Failure path
                logger.error(e) { "Failed to execute compensation for ${toLogString()}" }
                negAcknowledgeWithRetry(e, workflowName, workflowVersion)
                onFailureTest(this, e)
            }
            Result.failure(compensation)
        } catch (e: Exception) {
            // Failure path
            logger.error(e) { "Failed to process ${toLogString()}" }
            negAcknowledgeWithRetry(e, workflowName, workflowVersion)
            onFailureTest(this, e)
            Result.failure(e)
        }
    }

    /**
     * Acknowledges the current message with retry logic.
     *
     * If the acknowledgment fails, an exception is thrown to trigger a broker reconnection
     */
    suspend fun Message<String>.acknowledgeWithRetry(
        workflowName: WorkflowName?,
        workflowVersion: WorkflowVersion?
    ) = try {
        ackWithRetry()
        logger.debug { "Message ACKed: ${toLogString()}" }
        metrics.ackCompleted(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to ACK message: ${toLogString()}" }
        metrics.ackFailed(workflowName, workflowVersion)
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
        label = "ACK",
        maxAttempts = maxAttempts,
        totalBudgetMs = totalBudgetMs,
        singleAttemptTimeoutMs = singleAttemptTimeoutMs,
        onRetryStart = { readinessDownDuringRetries.set(true) },
        onFatalFailure = { _, _, _ -> livenessDownOnFatal.set(true) }
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
        workflowName: WorkflowName?,
        workflowVersion: WorkflowVersion?
    ) = try {
        nackWithRetry(e)
        logger.info(e) { "Message NACKed: ${toLogString()} - should be sent to the DLQ by brokers" }
        metrics.nackCompleted(workflowName, workflowVersion)
    } catch (e: Exception) {
        logger.error(e) { "Failed to NACK message: ${toLogString()}" }
        metrics.nackFailed(workflowName, workflowVersion)
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
        label = "NACK",
        maxAttempts = maxAttempts,
        totalBudgetMs = totalBudgetMs,
        singleAttemptTimeoutMs = singleAttemptTimeoutMs,
        onRetryStart = { readinessDownDuringRetries.set(true) },
        onFatalFailure = { _, _, _ -> livenessDownOnFatal.set(true) }
    ) {
        nackSuspending(cause)
    }

    /**
     * Retries the execution of a given suspending block of code until it succeeds, the maximum number of attempts
     * is reached, or the total time budget in milliseconds is exceeded. The retry logic includes handling exceptions
     * and applying an exponential backoff mechanism to space out subsequent attempts.
     *
     * This is a generic retry function that can be reused across different operations.
     *
     * @param label A descriptive label for the retry operation, used in logging to track the operation being attempted.
     * @param maxAttempts The maximum number of retry attempts allowed before giving up.
     * @param totalBudgetMs The total allowable time budget for retries, in milliseconds, after which retries will stop.
     * @param onRetryStart Called when starting a retry attempt (attempt > 0). Optional.
     * @param onFatalFailure Called when all retries are exhausted. Optional.
     * @param block The suspending block of code to execute and retry upon failure.
     */

    suspend fun retry(
        label: String,
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 30_000,
        singleAttemptTimeoutMs: Long = 15_000,
        onRetryStart: (() -> Unit)? = null,
        onFatalFailure: ((Exception, Int, Long) -> Unit)? = null,
        block: suspend () -> Unit
    ) {
        var attempt = 0
        val start = System.nanoTime()
        while (true) {
            try {
                if (attempt > 0) onRetryStart?.invoke()
                withTimeout(singleAttemptTimeoutMs) {
                    block()
                }
                return
            } catch (e: Exception) {
                attempt++
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                val retriable = isRetriable(e)
                if (!retriable || attempt >= maxAttempts || elapsedMs >= totalBudgetMs) {
                    logger.error(e) { "$label failed after $attempt attempts (${elapsedMs}ms)" }
                    onFatalFailure?.invoke(e, attempt, elapsedMs)
                    throw e
                }
                sleepBackoff(attempt)
            }
        }
    }

    /**
     * Determines whether the given throwable is considered retriable based on its root cause.
     *
     * @param t The throwable to evaluate.
     * @return True if the throwable is retriable, false otherwise.
     */
    private fun isRetriable(t: Throwable): Boolean {
        val c = rootCause(t)
        // We check class names for RabbitMQ exceptions as we cannot add imports here.
        val isRabbitMqTransient = when (c?.javaClass?.name) {
            "com.rabbitmq.client.ShutdownSignalException",
            "com.rabbitmq.client.AlreadyClosedException" -> true

            else -> false
        }
        val isSqlTransient = c?.javaClass?.name?.startsWith("java.sql.SQLTransient") == true

        return c is TimeoutCancellationException || // <- coroutine timeout exception
            c is RetriableException || // <- kafka exception
            c is java.io.IOException || // <- IO exception (e.g. connection reset)
            isRabbitMqTransient || isSqlTransient
    }

    private fun rootCause(t: Throwable?): Throwable? {
        var e = t
        while (e is ExecutionException || e is CompletionException) e = e.cause
        return e ?: t
    }

    /**
     * Suspends the execution of the coroutine for a time duration that increases exponentially
     * based on the retry attempt. This backoff mechanism helps in mitigating frequent retries
     * in case of repeated failures.
     *
     * @param attempt The number of retry attempts made so far, zero-based. This determines the backoff delay duration.
     * @param minMs The minimum duration in milliseconds for the initial backoff delay. Default value is 100 milliseconds.
     * @param maxMs The maximum duration in milliseconds for the backoff delay, preventing excessive delay. Default value is 5000 milliseconds.
     */
    private suspend fun sleepBackoff(attempt: Int, minMs: Long = 100, maxMs: Long = 5_000) {
        val pow = 1L shl attempt.coerceAtMost(5) // 1,2,4,8,16,32
        val base = (minMs * pow).coerceAtMost(maxMs)
        val jitter = Random.nextLong(0, base / 2 + 1)
        delay(base + jitter)
    }

    companion object {
        private const val UNKNOWN = ""
        val UNKNOWN_NAME = WorkflowName(UNKNOWN)
        val UNKNOWN_VERSION = WorkflowVersion(UNKNOWN)
    }
}

class CompensationException(val reason: String, val run: suspend () -> Unit) : RuntimeException()
