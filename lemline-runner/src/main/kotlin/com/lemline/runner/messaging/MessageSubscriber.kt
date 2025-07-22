// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.warn
import com.lemline.runner.metrics.MessageSubscriberMetrics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.slf4j.Logger

/**
 * A reactive message subscriber that processes messages asynchronously with controlled parallelism.
 *
 * This subscriber implements the Reactive Streams Subscriber interface and provides:
 * - Controlled parallel processing using a semaphore
 * - Basic, non-dimensional metrics tracking (received, active, saturated)
 * - Error handling and logging for the message lifecycle
 * - Backpressure management
 *
 * @param P The type of the message payload
 * @param T The type of the reactive message that wraps the payload
 * @param publisher The source of messages to subscribe to
 * @param handleMessage Suspending function that processes each message payload
 * @param maxParallelism Maximum number of messages that can be processed concurrently
 * @param metrics Metrics collector for monitoring processing statistics
 * @param logger Logger instance for recording events and errors
 */

internal class MessageSubscriber<P, T : ReactiveMessage<P>>(
    private val publisher: Publisher<T>,
    private val handleMessage: suspend (payload: P) -> Unit,
    private val maxParallelism: Int,
    private val metrics: MessageSubscriberMetrics,
    private val logger: Logger
) : Subscriber<T> {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
        logger.error(throwable) { "Unhandled coroutine exception in consumer" }
    })

    private val semaphore = Semaphore(maxParallelism)

    private lateinit var subscription: Subscription

    // Add state tracking
    private var isSubscribed = false
    private var isShutdown = false


    fun subscribe() = synchronized(this) {
        if (isSubscribed) {
            logger.warn { "Subscribe called more than once - ignoring" }
            return
        }
        isSubscribed = true
        publisher.subscribe(this)
    }

    override fun onSubscribe(s: Subscription) = synchronized(this) {
        if (isShutdown) {
            s.cancel()
            return
        }
        subscription = s
        subscription.request(maxParallelism.toLong())
    }

    override fun onNext(item: T) {
        metrics.received()

        val job = scope.launch {
            if (!semaphore.tryAcquire()) {
                metrics.saturated()
                // Wait for a slot to become available
                semaphore.acquire()
            }

            metrics.incrementActive()
            try {
                // The handler is now fully responsible for its own detailed, dimensional metrics.
                handleMessage(item.payload)
                // If the handler completes without an exception, we acknowledge the message.
                acknowledge(item)
            } catch (e: Exception) {
                // The handler should have already recorded the specific failure metric.
                // We just ensure the message is unacknowledged.
                unacknowledge(item, e)
            } finally {
                semaphore.release()
                metrics.decrementActive()
                requestNext()
            }
        }
        // Check if the job was canceled immediately (meaning the scope was already canceled)
        if (job.isCancelled) {
            logger.warn { "Received message after subscriber shutdown, rejecting: $item" }
            unacknowledge(item, IllegalStateException("Subscriber already shut down"))
        }
    }

    override fun onError(t: Throwable) {
        logger.error(t) { "Error on subscription" }
        onShutdown()
    }

    override fun onComplete() {
        logger.info { "Subscription completed" }
        onShutdown()
    }

    internal fun onShutdown(timeoutMs: Long = 5000) = synchronized(this) {
        if (isShutdown) return
        isShutdown = true
        cancelSubscription()
        // wait for active messages to complete
        gracePeriod(timeoutMs)
    }

    private fun requestNext() = try {
        subscription.request(1)
    } catch (e: Exception) {
        logger.warn(e) { "Failed to request next message" }
    }

    private fun acknowledge(item: T) = try {
        item.ack()
        metrics.acknowledged()
    } catch (e: Exception) {
        logger.error(e) { "Failed to ack message: $item" }
    }

    private fun unacknowledge(item: T, reason: Exception) = try {
        item.nack(reason)
        metrics.unacknowledged()
    } catch (e: Exception) {
        logger.error(e) { "Failed to nack message (after ${reason.message}): $item" }
    }

    private fun cancelSubscription() {
        if (::subscription.isInitialized) {
            try {
                logger.info { "🔻 Shutting down consumer" }
                subscription.cancel()
            } catch (e: Exception) {
                logger.error(e) { "Error canceling subscription" }
            }
        }
    }

    private fun gracePeriod(timeoutMs: Long) {
        // Launch a coroutine to handle the shutdown
        CoroutineScope(Dispatchers.Default).future {
            try {
                // Wait for active messages to complete (with timeout)
                withTimeout(timeoutMs) {
                    while (metrics.getActiveCount() > 0) {
                        delay(100)
                    }
                }
                logger.info { "✅ All messages processed, completing shutdown" }
            } catch (_: TimeoutCancellationException) {
                logger.warn { "⚠️ Graceful shutdown timed out with ${metrics.getActiveCount()} messages still active" }
            } finally {
                scope.cancel()
            }
        }.join()
    }
}
