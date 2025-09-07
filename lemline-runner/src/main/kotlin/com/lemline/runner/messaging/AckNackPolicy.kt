// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.logger
import io.quarkus.smallrye.reactivemessaging.ackSuspending
import io.quarkus.smallrye.reactivemessaging.nackSuspending
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMetadata
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.kafka.common.errors.RetriableException
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.eclipse.microprofile.health.Readiness
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * The AckNackPolicy centralizes how this service acknowledges (ACK) or negatively acknowledges (NACK)
 * messages in a production-safe way. The philosophy is simple:
 * - For ACK errors: after bounded retries with exponential backoff, we stop consumption and mark the
 *   service unhealthy, because an ACK failure means the platform itself cannot reliably commit offsets.
 * - For NACK errors: if the configured failure strategy (e.g. DLQ) works, we let it handle the message.
 *   If it fails repeatedly, we fall back to a local quarantine (durable file) to preserve the payload,
 *   then force an ACK to advance the offset and keep processing.
 *
 * This approach guarantees that the system never blocks indefinitely on a poison pill, never drops
 * messages silently, and always provides operators with a trace (DLQ or quarantine) to recover from.
 *
 *
 * Usage in Subscriber.onNext:
 *
 * try {
 *     process(msg.payload, md)
 *     if (ackWithRetry(msg)) {
 *         // ack succeeded - continue to next message
 *         subscription?.request(1)
 *     } else {
 *         // ack failed - cancel the subscription
 *         subscription?.cancel()
 *         onError(IllegalStateException("ACK permanently failed"))
 *     }
 * } catch (cause: Exception) {
 *     if (nackWithRetry(msg, cause)) {
 *         // nack succeeded - (message saved on DLQ) - continue to next message
 *         subscription?.request(1)
 *     } else {
 *          // nack failed - quarantine the message locally
 *         val saved = AckNackPolicy.quarantineLocally(msg, cause)
 *         if (saved && AckNackPolicy.ackWithRetry(msg)) {
 *             // if message saved locally and ack succeeded - continue to next message
 *             subscription?.request(1)
 *         } else {
 *             // else stop consumption and mark the service unhealthy
 *             subscription?.cancel()
 *             onError(IllegalStateException("Forced-ACK after quarantine failed"))
 *         }
 *     }
 * }
 *
 */
@Suppress("unused")
object AckNackPolicy {
    val logger = logger()

    // Health flags (optional): expose via Quarkus SmallRye Health
    val readinessDownDuringRetries = AtomicBoolean(false)
    val livenessDownOnFatal = AtomicBoolean(false)

    /**
     * Acknowledges a message with retry logic, ensuring retries with exponential backoff if the acknowledgment fails.
     * The method implements local retries to keep acknowledgment and processing within the defined time and attempt limits.
     *
     * @param maxAttempts The maximum number of retry attempts for acknowledgment. Defaults to 6.
     * @param totalBudgetMs The total allowable time budget for acknowledgment retries, in milliseconds. Defaults to 60,000 ms.
     * @param singleAttemptTimeoutMs The timeout for a single acknowledgment attempt, in milliseconds. Defaults to 10,000 ms.
     */
    suspend fun Message<*>.ackWithRetry(
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 6_000, // Keep local retry+processing under throttled.unprocessed-record-max-age.ms
        singleAttemptTimeoutMs: Long = 1_000
    ) = retry("ACK", maxAttempts, totalBudgetMs) {
        withTimeout(singleAttemptTimeoutMs) {
            ackSuspending()
        }
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
    suspend fun Message<*>.nackWithRetry(
        cause: Throwable,
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 6_000,
        singleAttemptTimeoutMs: Long = 1_000
    ) = retry("NACK", maxAttempts, totalBudgetMs) {
        withTimeout(singleAttemptTimeoutMs) {
            nackSuspending(cause)
        }
    }

    /**
     * Writes a message and its associated metadata to a local quarantine file for later inspection.
     *
     * @param this The message to be quarantined, including its metadata and payload.
     * @param cause The throwable that caused the message to be quarantined.
     * @param path Optional file path to the quarantine file. Defaults to "/var/lib/lemline/quarantine.jsonl".
     * @return True if the message was successfully written to the quarantine file, false otherwise.
     */
    fun Message<*>.quarantineLocally(
        cause: Throwable,
        path: String = "/var/lib/lemline/quarantine.jsonl"
    ): Boolean = try {
        val kafkaMd = getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)
        val rabbitMd = getMetadata(IncomingRabbitMQMetadata::class.java).orElse(null)
        val json = buildString {
            append('{')
            append("\"ts\":\"").append(Instant.now()).append('\"')
            if (kafkaMd != null) {
                append(",\"topic\":\"").append(kafkaMd.topic).append('\"')
                append(",\"partition\":").append(kafkaMd.partition)
                append(",\"offset\":").append(kafkaMd.offset)
                append(",\"headers\":").append(kafkaMd.headers.toString().replace("\"", "\\\""))
                append(",\"key\":").append(kafkaMd.key ?: "null")
            }
            if (rabbitMd != null) {
                append(",\"exchange\":\"").append(rabbitMd.exchange ?: "").append('\"')
                append(",\"routingKey\":\"").append(rabbitMd.routingKey ?: "").append('\"')
                append(",\"headers\":").append(rabbitMd.headers.toString().replace("\"", "\\\""))
            }
            append(",\"error\":\"").append(rootCause(cause)?.javaClass?.name).append('\"')
            append(",\"errorMessage\":\"").append((rootCause(cause)?.message ?: "").replace("\"", "\\\"")).append('\"')
            append(",\"payload\":").append(payload.toString().replace("\"", "\\\""))
            append('}').append('\n')
        }
        Files.createDirectories(Paths.get(path).parent)
        Files.writeString(
            Paths.get(path), json, StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )
        logger.warn(cause) { "Message ${toLogString()} quarantined to $path" }
        true
    } catch (t: Exception) {
        logger.error(t) { "Failed to write message ${toLogString()} to quarantine file $path" }
        false
    }

    /**
     * Retries the execution of a given suspending block of code until it succeeds, the maximum number of attempts
     * is reached, or the total time budget in milliseconds is exceeded. The retry logic includes handling exceptions
     * and applying an exponential backoff mechanism to space out subsequent attempts.
     *
     * @param label A descriptive label for the retry operation, used in logging to track the operation being attempted.
     * @param maxAttempts The maximum number of retry attempts allowed before giving up.
     * @param totalBudgetMs The total allowable time budget for retries, in milliseconds, after which retries will stop.
     * @param block The suspending block of code to execute and retry upon failure.
     */
    private suspend fun retry(
        label: String,
        maxAttempts: Int,
        totalBudgetMs: Long,
        block: suspend () -> Unit
    ) {
        var attempt = 0
        val start = System.nanoTime()
        while (true) {
            try {
                if (attempt > 0) readinessDownDuringRetries.set(true)
                block()
                readinessDownDuringRetries.set(false)
                return
            } catch (e: Exception) {
                attempt++
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                val retriable = isRetriable(e)
                if (!retriable || attempt >= maxAttempts || elapsedMs >= totalBudgetMs) {
                    logger.error(e) { "$label failed after $attempt attempts (${elapsedMs}ms)" }
                    livenessDownOnFatal.set(true)
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
        return c is TimeoutCancellationException || // <- coroutine timeout exception
            c is RetriableException || // <- kafka exception
            c is java.io.IOException // <- IO exception (e.g. connection reset)
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
}

@Readiness
class RetryReadiness : HealthCheck {
    override fun call(): HealthCheckResponse =
        if (AckNackPolicy.readinessDownDuringRetries.get())
            HealthCheckResponse.down("orders-readiness")
        else
            HealthCheckResponse.up("orders-readiness")
}

@Liveness
class FatalAckLiveness : HealthCheck {
    override fun call(): HealthCheckResponse =
        if (AckNackPolicy.livenessDownOnFatal.get())
            HealthCheckResponse.down("orders-liveness")
        else
            HealthCheckResponse.up("orders-liveness")
}
