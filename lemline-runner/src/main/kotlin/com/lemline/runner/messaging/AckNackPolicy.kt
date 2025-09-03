package com.lemline.runner.messaging

import com.lemline.common.error
import com.lemline.common.logger
import com.lemline.common.warn
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
     * A function that attempts to acknowledge a message with retries in case of failure.
     * The function will make several attempts to acknowledge the message within the provided
     * retry constraints, such as the maximum number of attempts, the total time budget, and
     * the timeout for an individual attempt.
     *
     * @param msg The message to be acknowledged. Must be an instance of a reactive messaging Message.
     * @param maxAttempts The maximum number of retry attempts to perform. Defaults to 6.
     * @param totalBudgetMs The total time budget (in milliseconds) allowed for all retry attempts. Defaults to 60,000 ms.
     * @param singleAttemptTimeoutMs The timeout duration (in milliseconds) for a single acknowledgment attempt. Defaults to 10,000 ms.
     * @return True if the message was successfully acknowledged within the retry constraints, otherwise false.
     */
    suspend fun ackWithRetry(
        msg: Message<*>,
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 60_000, // Keep local retry+processing under throttled.unprocessed-record-max-age.ms
        singleAttemptTimeoutMs: Long = 10_000
    ): Boolean = retry("ACK", maxAttempts, totalBudgetMs) {
        withTimeout(singleAttemptTimeoutMs) {
            msg.ackSuspending()
        }
    }

    /**
     * Handles a negative acknowledgment (NACK) of a reactive message with retry logic.
     * The method retries the NACK operation up to a specified number of attempts within
     * a time budget, using a timeout for each individual attempt.
     *
     * @param msg The reactive message to be negatively acknowledged.
     * @param cause The throwable that triggered the negative acknowledgment.
     * @param maxAttempts The maximum number of retry attempts for the NACK operation. Default is 6.
     * @param totalBudgetMs The total time budget in milliseconds across all retry attempts. Default is 60,000ms.
     * @param singleAttemptTimeoutMs The timeout in milliseconds for each individual retry attempt. Default is 10,000ms.
     * @return True if the NACK operation succeeds within the constraints, false otherwise.
     */
    suspend fun nackWithRetry(
        msg: Message<*>,
        cause: Throwable,
        maxAttempts: Int = 6,
        totalBudgetMs: Long = 60_000,
        singleAttemptTimeoutMs: Long = 10_000
    ): Boolean = retry("NACK", maxAttempts, totalBudgetMs) {
        withTimeout(singleAttemptTimeoutMs) {
            msg.nackSuspending(cause)
        }
    }

    /**
     * Writes a message and its associated metadata to a local quarantine file for later inspection.
     *
     * @param msg The message to be quarantined, including its metadata and payload.
     * @param cause The throwable that caused the message to be quarantined.
     * @param path Optional file path to the quarantine file. Defaults to "/var/lib/lemline/quarantine.jsonl".
     * @return True if the message was successfully written to the quarantine file, false otherwise.
     */
    fun quarantineLocally(
        msg: Message<*>,
        cause: Throwable,
        path: String = "/var/lib/lemline/quarantine.jsonl"
    ): Boolean = try {
        val kafkaMd = msg.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)
        val rabbitMd = msg.getMetadata(IncomingRabbitMQMetadata::class.java).orElse(null)
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
            append(",\"payload\":").append(msg.payload.toString().replace("\"", "\\\""))
            append('}').append('\n')
        }
        Files.createDirectories(Paths.get(path).parent)
        Files.writeString(
            Paths.get(path), json, StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )
        logger.warn(cause) { "Message ${msg.toLogString()} quarantined to $path" }
        true
    } catch (t: Exception) {
        logger.error(t) { "Failed to write message ${msg.toLogString()} to quarantine file $path" }
        false
    }

    /**
     * Retries the execution of a block of code with a configurable maximum number of attempts and
     * a time budget, using an exponential backoff strategy between attempts. The method ensures
     * that the retry process stops on reaching the maximum attempts, exceeding the time budget,
     * or encountering a non-retriable exception.
     *
     * @param label A descriptive label used in logging to identify the operation being retried.
     * @param maxAttempts The maximum number of retry attempts allowed. Once reached, the method stops retrying.
     * @param totalBudgetMs The total time budget in milliseconds within which all retries must be performed. Once exceeded, the method stops retrying.
     * @param block A suspending lambda expression that contains the code to be executed and potentially retried upon failure.
     * @return True if the operation completes successfully within the retry constraints, false otherwise.
     */
    private suspend fun retry(
        label: String,
        maxAttempts: Int,
        totalBudgetMs: Long,
        block: suspend () -> Unit
    ): Boolean {
        var attempt = 0
        val start = System.nanoTime()
        while (true) {
            try {
                if (attempt > 0) readinessDownDuringRetries.set(true)
                block()
                readinessDownDuringRetries.set(false)
                return true
            } catch (e: Exception) {
                attempt++
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                val retriable = isRetriable(e)
                if (!retriable || attempt >= maxAttempts || elapsedMs >= totalBudgetMs) {
                    logger.error(e) { "$label failed after $attempt attempts (${elapsedMs}ms)" }
                    livenessDownOnFatal.set(true)
                    return false
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
        return c is RetriableException ||
            c is java.net.SocketTimeoutException ||
            c is java.nio.channels.ClosedChannelException
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
