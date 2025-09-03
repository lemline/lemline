// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.error
import com.lemline.common.info
import com.lemline.common.logger
import com.lemline.common.warn
import com.lemline.runner.models.WorkflowIdentification
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

@ExperimentalTime
internal abstract class MessageSubscriber<T : WorkflowIdentification>() : Subscriber<Message<String>> {

    abstract val maxConcurrency: Long
    abstract val enabled: Boolean
    abstract val publisher: Publisher<Message<String>>
    abstract val handler: MessageHandler<T>
    abstract val metrics: MessageSubscriberMetrics

    val logger = logger()

    private val scope: CoroutineScope =
        CoroutineScope(
            Dispatchers.IO +
                SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    if (throwable is Exception && isConnectionError(throwable)) {
                        logger.error(throwable) { "Critical connection error in consumer - triggering shutdown" }
                        // Trigger a shutdown of the subscriber
                        CoroutineScope(Dispatchers.Default).launch { onShutdown() }
                    } else {
                        logger.error(throwable) { "Unhandled coroutine exception in consumer" }
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

    @PreDestroy
    fun shutdown() {
        onShutdown()
    }

    private lateinit var subscription: Subscription

    // Add state tracking
    private var isSubscribed = AtomicBoolean(false)
    private var isShutdown = AtomicBoolean(false)


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
    }

    override fun onNext(item: Message<String>) {
        if (isShutdown.get()) {
            logger.warn { "Received message after subscriber shutdown. It will be redelivered by the broker. Message: $item" }
        } else {
            scope.launch {
                metrics.received()
                metrics.incrementActive()

                try {
                    handler.handleMessage(item)
                } finally {
                    metrics.decrementActive()
                    requestNext()
                }
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

    internal fun onShutdown(timeoutMs: Long = 5000) {
        if (!isShutdown.compareAndSet(false, true)) return
        cancelSubscription()
        // wait for active messages to complete
        gracePeriod(timeoutMs)
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

private fun isConnectionError(e: Exception): Boolean = when (e) {
    is java.io.IOException,
    is java.util.concurrent.TimeoutException -> true

    // Kafka specific exceptions
    is org.apache.kafka.common.errors.NetworkException,
    is org.apache.kafka.common.errors.DisconnectException,
    is org.apache.kafka.common.errors.TimeoutException -> true

    // RabbitMQ specific exceptions
    is com.rabbitmq.client.ShutdownSignalException -> true

    else -> {
        // Recursive check for the cause
        val cause = e.cause
        when {
            cause == null -> false
            cause === e -> false  // Avoid infinite loop
            cause is Exception -> isConnectionError(cause)
            else -> false
        }
    }
}
