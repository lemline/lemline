// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.core.logger.logger
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
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

@ExperimentalTime
internal abstract class MessageSubscriber<T : WorkflowMessage>() : Subscriber<Message<String>> {

    abstract val maxConcurrency: Long
    abstract val enabled: Boolean
    abstract val publisher: Publisher<Message<String>>
    abstract val handler: MessageHandler<T>
    abstract val metrics: MessageSubscriberMetrics

    val logger = logger()

    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            logger.warn(e) { "Error processing message, will attempt to recover." }
            // restart the subscription on another coroutine
            reSubscribe()
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

    @PreDestroy
    fun shutdown() {
        onShutdown()
    }

    private lateinit var subscription: Subscription

    // Add state tracking
    private var isResubscribing = AtomicBoolean(false)
    private var isSubscribed = AtomicBoolean(false)
    private var isShutdown = AtomicBoolean(false)

    internal fun reSubscribe(timeoutMs: Long = 5000) {
        if (!isResubscribing.compareAndSet(false, true)) {
            logger.warn { "reSubscribe called more than once - ignoring" }
            return
        }
        cancelSubscription()
        // wait for active messages to complete
        gracePeriod(timeoutMs)
        // restart the subscription
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
            logger.warn { "Received message after subscriber shutdown. It will be redelivered by the broker. Message: $item" }
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
        logger.error(t) { "Error on subscription" }
        reSubscribe()
    }

    override fun onComplete() {
        logger.info { "Subscription completed" }
        onShutdown()
    }

    internal fun onShutdown(timeoutMs: Long = 5000) {
        if (!isShutdown.compareAndSet(false, true)) {
            logger.warn { "onShutdown called more than once - ignoring" }
            return
        }

        cancelSubscription()
        // wait for active messages to complete
        gracePeriod(timeoutMs)
        // shutdown the scope
        scope.cancel()
    }

    private fun requestNext() {
        try {
            // Only request more if we are not shutting down
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

    private fun gracePeriod(timeoutMs: Long) {
        // Launch a coroutine to handle the shutdown
        runBlocking {
            try {
                withTimeout(timeoutMs) {
                    // Wait for coroutines currently processing messages
                    scope.coroutineContext.job.children.forEach { it.join() }

                    // check also metrics
                    while (metrics.getActiveCount() > 0) {
                        delay(50)
                    }
                }
                logger.info { "✅ All messages processed, completing shutdown" }
            } catch (_: TimeoutCancellationException) {
                logger.warn { "⚠️ Graceful shutdown timed out with ${metrics.getActiveCount()} messages still active" }
                // Force cancel the remaining jobs
                scope.coroutineContext.job.cancelChildren()
            }
        }
    }
}
