// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.Logger
import java.io.IOException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.kafka.common.errors.RetriableException

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
 * @param singleAttemptTimeoutMs The timeout in milliseconds for each individual retry attempt.
 * @param onRetry Called when starting a retry attempt (attempt > 0). Optional.
 * @param onSuccess Called when the operation succeeds (after block completes successfully). Optional.
 * @param onFailure Called when all retries are exhausted. Optional.
 * @param block The suspending block of code to execute and retry upon failure.
 */
@ExperimentalTime
suspend fun <T> retry(
    logger: Logger,
    label: String,
    maxAttempts: Int = 6,
    totalBudgetMs: Long = 30_000,
    singleAttemptTimeoutMs: Long = 15_000,
    onRetry: (() -> Unit)? = null,
    onSuccess: ((T) -> Unit)? = null,
    onFailure: ((Exception, Int, Long) -> Unit) = { e: Exception, _, _ -> throw e },
    block: suspend () -> T
): T {
    var attempt = 0
    val start = System.nanoTime()
    while (true) {
        try {
            if (attempt > 0) onRetry?.invoke()
            val result: T = withTimeout(singleAttemptTimeoutMs) {
                block()
            }
            onSuccess?.invoke(result)
            return result
        } catch (e: Exception) {
            attempt++
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            if (!isRetriable(e) || attempt >= maxAttempts || elapsedMs >= totalBudgetMs) {
                logger.error(e) { "$label failed after $attempt attempts (${elapsedMs}ms)" }
                onFailure.invoke(e, attempt, elapsedMs)
                throw e
            }
            logger.debug(e) { "$label failed at $attempt attempt (${elapsedMs}ms) - retrying" }
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
        c is IOException || // <- IO exception (e.g. connection reset)
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
