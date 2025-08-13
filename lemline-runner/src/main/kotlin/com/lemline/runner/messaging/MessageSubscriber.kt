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
 * A reactive message subscriber that processes messages asynchronously with controlled concurrency.
 *
 * This subscriber is a generic dispatcher. It delegates the entire message lifecycle,
 * including processing and acknowledgement, to the provided `handleMessage` function.
 *
 * @param P The type of the message payload
 * @param T The type of the reactive message that wraps the payload
 * @param publisher The source of messages to subscribe to
 * @param handleMessage Suspending function that handles the full lifecycle of each message.
 * @param maxConcurrency Maximum number of messages that can be processed concurrently
 * @param metrics Metrics collector for monitoring processing statistics
 * @param logger Logger instance for recording events and errors
 */

internal class MessageSubscriber<P, T : ReactiveMessage<P>>(
    private val publisher: Publisher<T>,
    private val handleMessage: suspend (item: T) -> Unit, // Changed signature
    private val maxConcurrency: Int,
    private val metrics: MessageSubscriberMetrics,
    private val logger: Logger
) : Subscriber<T> {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
        logger.error(throwable) { "Unhandled coroutine exception in consumer" }
    })

    private val semaphore = Semaphore(maxConcurrency)

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
        subscription.request(maxConcurrency.toLong())
    }

    override fun onNext(item: T) {
        // Record metric as soon as the message is received from the stream.
        metrics.received()

        // Check if we are already shutting down before launching a new coroutine.
        if (isShutdown) {
            // The scope is shutting down. We cannot process this message.
            // By not acknowledging or unacknowledging it, we rely on the broker's
            // standard redelivery mechanism.
            logger.warn { "Received message after subscriber shutdown. It will be redelivered by the broker. Message: $item" }
            return
        }

        scope.launch {
            if (!semaphore.tryAcquire()) {
                metrics.saturated()
                // Wait for a slot to become available
                semaphore.acquire()
            }

            metrics.incrementActive()
            try {
                // Delegate the entire message lifecycle to the handler.
                // The handler is responsible for processing, ack/nack, and its own error handling.
                handleMessage(item)
            } catch (e: Exception) {
                // This is a safety net. The handler should not throw exceptions.
                // If it does, it indicates a bug in the handler's own try/catch logic.
                logger.error(e) { "CRITICAL: Unhandled exception from message handler. The message's state is now unknown and it may be redelivered or lost." }
            } finally {
                semaphore.release()
                metrics.decrementActive()
                requestNext()
            }
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

    private fun requestNext() {
        try {
            // Only request more if we are not shutting down
            if (!isShutdown) subscription.request(1)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to request next message" }
        }
    }

    // acknowledge and unacknowledge methods are now removed from this class.

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
