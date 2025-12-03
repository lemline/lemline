// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.logger.logger
import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.healthcheck.FatalAckLiveness.livenessDownOnFailure
import com.lemline.runner.healthcheck.RetryReadiness.readinessDownDuringRetries
import com.lemline.runner.messaging.retry
import com.lemline.runner.messaging.toLogString
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.Startup
import io.quarkus.smallrye.reactivemessaging.ackSuspending
import io.quarkus.smallrye.reactivemessaging.nackSuspending
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
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
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Message
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

internal const val CLOUDEVENTS_IN_CHANNEL = "cloudevents-in"

/**
 * Subscribes to incoming CloudEvents and processes them by matching against active listeners.
 *
 * CloudEvents are consumed from the `cloudevents-in` channel, parsed using the CloudEvents SDK,
 * and then passed to the [CloudEventHandler] for processing.
 *
 * Unlike workflow message subscribers, CloudEvents don't follow a strict request-response pattern.
 * Each event may trigger zero, one, or multiple listener completions.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@Startup
@ApplicationScoped
internal class CloudEventSubscriber(
    @param:ConfigProperty(name = CLOUDEVENTS_CONSUMER_CONCURRENCY) private val maxConcurrency: Long,
    @param:ConfigProperty(name = CLOUDEVENTS_CONSUMER_ENABLED) private val enabled: Boolean,
    @param:Channel(CLOUDEVENTS_IN_CHANNEL) private val publisher: Publisher<Message<String>>,
    private val handler: CloudEventHandler,
    private val metrics: CloudEventSubscriberMetrics,
) : Subscriber<Message<String>> {

    private val logger = logger()
    private val gracePeriod = 5000L
    private val cloudEventFormat = JsonFormat()

    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            if (!isShutdown.get() && (e is Exception)) {
                logger.warn(e) { "Error processing CloudEvent, will attempt to recover." }
                reSubscribe()
            }
        })

    @PostConstruct
    fun init() {
        if (enabled) {
            logger.info { "✅ CloudEvent consumer enabled" }
            subscribe()
        } else {
            logger.info { "❌ CloudEvent consumer disabled" }
        }
    }

    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.info { "🛑 ShutdownEvent received for CloudEvent consumer - initiating graceful shutdown" }
        performGracefulShutdown()
    }

    private lateinit var subscription: Subscription
    private var isResubscribing = AtomicBoolean(false)
    private var isSubscribed = AtomicBoolean(false)
    private var isShutdown = AtomicBoolean(false)

    private fun reSubscribe() {
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

    private fun subscribe() {
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
            logger.warn { "Received CloudEvent after subscriber shutdown. It will be redelivered by the broker." }
            return
        }

        scope.launch {
            metrics.received()
            metrics.incrementActive()
            try {
                processMessage(item)
            } finally {
                metrics.decrementActive()
            }
            requestNext()
        }
    }

    override fun onError(t: Throwable) {
        if (!isShutdown.get()) {
            logger.error(t) { "Error on CloudEvent subscription" }
            reSubscribe()
        }
    }

    override fun onComplete() {
        logger.info { "CloudEvent subscription completed" }
    }

    /**
     * Processes a single CloudEvent message.
     */
    private suspend fun processMessage(message: Message<String>) {
        try {
            logger.debug { "Received CloudEvent: ${message.toLogString()}" }

            // Parse CloudEvent from JSON
            val cloudEvent = metrics.recordDeserializationDuration {
                deserializeCloudEvent(message.payload)
            }

            // Handle the CloudEvent
            metrics.recordProcessingDuration(null) {
                handler.handleCloudEvent(cloudEvent)
            }

            // Acknowledge the message
            acknowledgeWithRetry(message)

        } catch (e: Exception) {
            logger.error(e) { "Failed to process CloudEvent: ${message.toLogString()}" }
            negAcknowledgeWithRetry(message, e)
        }
    }

    /**
     * Deserializes a CloudEvent from JSON string.
     */
    private fun deserializeCloudEvent(payload: String): CloudEvent {
        return cloudEventFormat.deserialize(payload.toByteArray())
    }

    private suspend fun acknowledgeWithRetry(message: Message<String>) = try {
        retry(
            logger = logger,
            label = "ACK",
            maxAttempts = 6,
            totalBudgetMs = 6_000,
            singleAttemptTimeoutMs = 1_000,
            onRetry = { readinessDownDuringRetries.set(true) },
            onSuccess = { readinessDownDuringRetries.set(false) },
            onFailure = { _, _, _ -> livenessDownOnFailure.set(true) }
        ) {
            message.ackSuspending()
        }
        logger.debug { "CloudEvent ACKed: ${message.toLogString()}" }
        metrics.ackCompleted(null)
    } catch (e: Exception) {
        logger.error(e) { "Failed to ACK CloudEvent: ${message.toLogString()}" }
        metrics.ackFailed(null)
        throw e
    }

    private suspend fun negAcknowledgeWithRetry(message: Message<String>, cause: Throwable) = try {
        retry(
            logger = logger,
            label = "NACK",
            maxAttempts = 6,
            totalBudgetMs = 6_000,
            singleAttemptTimeoutMs = 1_000,
            onRetry = { readinessDownDuringRetries.set(true) },
            onSuccess = { readinessDownDuringRetries.set(false) },
            onFailure = { _, _, _ -> livenessDownOnFailure.set(true) }
        ) {
            message.nackSuspending(cause)
        }
        logger.warn { "CloudEvent NACKed: ${message.toLogString()}" }
        metrics.nackCompleted(null)
    } catch (e: Exception) {
        logger.error(e) { "Failed to NACK CloudEvent: ${message.toLogString()}" }
        metrics.nackFailed(null)
        throw e
    }

    private fun requestNext() {
        try {
            if (!isShutdown.get()) subscription.request(1)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to request next CloudEvent" }
        }
    }

    private fun performGracefulShutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            logger.warn { "🛑 Graceful shutdown already initiated - ignoring" }
            return
        }
        logger.info { "🛑 Starting graceful shutdown for CloudEvent consumer" }
        cancelSubscription()
        gracefulWaitForCompletion()
        scope.cancel()
        logger.info { "🏁 CloudEvent consumer scope cancelled" }
    }

    private fun cancelSubscription() {
        if (::subscription.isInitialized) {
            try {
                logger.info { "🔻 Shutting down CloudEvent consumer" }
                subscription.cancel()
                isSubscribed.set(false)
            } catch (e: Exception) {
                logger.error(e) { "Error canceling CloudEvent subscription" }
            }
        }
    }

    private fun gracefulWaitForCompletion() = runBlocking {
        try {
            withTimeout(gracePeriod) {
                logger.info { "⏳ Waiting for ${metrics.getActiveCount()} active CloudEvents to complete" }
                scope.coroutineContext.job.children.forEach { it.join() }
                while (metrics.getActiveCount() > 0) {
                    delay(10)
                }
            }
            logger.info { "✅ All CloudEvents processed gracefully" }
        } catch (_: TimeoutCancellationException) {
            val remainingCount = metrics.getActiveCount()
            logger.warn { "⚠️ Graceful shutdown timed out with $remainingCount CloudEvents still active" }
            scope.coroutineContext.job.cancelChildren()
        } catch (e: Exception) {
            logger.error(e) { "💥 Error during graceful shutdown" }
        }
    }
}
