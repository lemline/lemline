// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.logger.logger
import io.quarkus.runtime.ShutdownEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.event.Observes
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * Base class for message subscribers providing subscription lifecycle management,
 * backpressure control, and graceful shutdown.
 *
 * This class handles all the infrastructure concerns:
 * - Subscription/unsubscription lifecycle
 * - Backpressure via request-based flow control
 * - Graceful shutdown with configurable timeout
 * - Coroutine scope management with supervisor job for error isolation
 * - Automatic re-subscription on errors
 *
 * Subclasses only need to provide:
 * - Configuration properties (maxConcurrency, enabled)
 * - The message publisher (channel)
 * - The message handler
 * - Metrics implementation
 *
 * @param T The message type that the handler processes
 */
@Suppress("ReactiveStreamsSubscriberImplementation")
@ExperimentalTime
internal abstract class MessageSubscriber<T> : Subscriber<Message<String>> {

    abstract val maxConcurrency: Long
    abstract val enabled: Boolean
    abstract val publisher: Publisher<Message<String>>
    abstract val handler: MessageHandler<T>
    abstract val metrics: MessageSubscriberMetrics

    val logger = logger()

    private val gracePeriod = 5000L

    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            if (!isShutdown.get() && (e is Exception)) {
                logger.warn(e) { "Error processing message, will attempt to recover." }
                reSubscribe()
            }
        })

    @PostConstruct
    fun init() {
        if (enabled) {
            logger.info { "✅ Consumer enabled" }
            subscribe()
        } else {
            logger.info { "❌ Consumer disabled" }
        }
    }

    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.info { "🛑 ShutdownEvent received - initiating graceful shutdown" }
        performGracefulShutdown()
    }

    private lateinit var subscription: Subscription

    private var isResubscribing = AtomicBoolean(false)
    private var isSubscribed = AtomicBoolean(false)
    private var isShutdown = AtomicBoolean(false)

    internal fun reSubscribe() {
        if (!isResubscribing.compareAndSet(false, true)) {
            logger.warn { "reSubscribe already ongoing - ignoring" }
            return
        }
        if (isSubscribed.get()) {
            cancelSubscription()
            gracefulWaitForCompletion()
        }
        subscribe()
    }

    internal fun subscribe() {
        if (!isSubscribed.compareAndSet(false, true)) {
            logger.warn { "Subscribe called more than once - ignoring" }
            return
        }
        publisher.subscribe(this)
    }

    override fun onSubscribe(s: Subscription) {
        if (isShutdown.get()) {
            s.cancel()
            return
        }
        subscription = s
        subscription.request(maxConcurrency)
        isResubscribing.set(false)
    }

    override fun onNext(item: Message<String>) {
        if (isShutdown.get()) {
            logger.warn { "Received message after subscriber shutdown. It will be redelivered by the broker. Message: ${item.toLogString()}" }
            return
        }

        scope.launch {
            metrics.received()
            metrics.incrementActive()
            try {
                handler.handleMessage(item)
            } finally {
                metrics.decrementActive()
            }
            requestNext()
        }
    }

    override fun onError(t: Throwable) {
        if (!isShutdown.get()) {
            logger.error(t) { "Error on subscription" }
            reSubscribe()
        }
    }

    override fun onComplete() {
        logger.info { "Subscription completed" }
    }

    private fun performGracefulShutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            logger.warn { "🛑 Graceful shutdown already initiated - ignoring" }
            return
        }

        logger.info { "🛑 Starting graceful shutdown" }
        cancelSubscription()
        gracefulWaitForCompletion()
        scope.cancel()
        logger.info { "🏁 scope cancelled" }
    }

    private fun requestNext() {
        try {
            if (!isShutdown.get()) subscription.request(1)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to request next message" }
        }
    }

    private fun cancelSubscription() {
        if (::subscription.isInitialized) {
            try {
                logger.info { "🔻 Shutting down consumer" }
                subscription.cancel()
                isSubscribed.set(false)
            } catch (e: Exception) {
                logger.error(e) { "Error canceling subscription" }
            }
        }
    }

    private fun gracefulWaitForCompletion() = runBlocking {
        try {
            withTimeout(gracePeriod) {
                logger.info { "⏳ Waiting for ${metrics.getActiveCount()} active messages to complete" }
                scope.coroutineContext.job.children.forEach { it.join() }
                while (metrics.getActiveCount() > 0) {
                    delay(10)
                }
            }
            logger.info { "✅ All messages processed gracefully" }
        } catch (_: TimeoutCancellationException) {
            val remainingCount = metrics.getActiveCount()
            logger.warn { "⚠️ Graceful shutdown timed out with $remainingCount messages still active" }
            scope.coroutineContext.job.cancelChildren()
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during graceful shutdown" }
        }
    }
}
