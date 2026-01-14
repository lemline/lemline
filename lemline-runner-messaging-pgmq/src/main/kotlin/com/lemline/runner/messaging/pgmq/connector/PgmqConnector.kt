// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq.connector

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.pgmq.PgmqClient
import com.lemline.runner.messaging.pgmq.PgmqMessage
import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.Startup
import io.smallrye.reactive.messaging.connector.InboundConnector
import io.smallrye.reactive.messaging.connector.OutboundConnector
import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mutiny.zero.flow.adapters.AdaptersToFlow
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector

/**
 * SmallRye Reactive Messaging connector for PGMQ (PostgreSQL Message Queue).
 *
 * This connector handles both incoming (consuming) and outgoing (producing) messages
 * using a PostgreSQL-based message queue using the PGMQ pattern.
 *
 * Configuration properties (prefixed with mp.messaging.{incoming|outgoing}.{channel}):
 * - host: PostgreSQL host
 * - port: PostgreSQL port
 * - database: Database name
 * - username: Database username
 * - password: Database password
 * - queue: PGMQ queue name
 * - visibility-timeout: Seconds before unacknowledged message becomes visible again (incoming only)
 * - poll-interval: Polling interval in milliseconds (incoming only)
 * - batch-size: Messages to fetch per poll (incoming only)
 *
 * @see PgmqConnectorConfig for all configuration options
 */
@Startup
@ApplicationScoped
@Connector(PgmqConnectorConfig.CONNECTOR_NAME)
class PgmqConnector : InboundConnector, OutboundConnector {

    companion object {
        private val logger = logger()
        private const val SHUTDOWN_TIMEOUT_MS = 5000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isShutdown = AtomicBoolean(false)

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var executionHolder: ExecutionHolder

    // Outgoing channel state
    private val outgoingClients = ConcurrentHashMap<String, PgmqClient>()
    private val outgoingSubscribers = ConcurrentHashMap<String, PgmqSubscriber>()

    // Incoming channel state
    private val incomingClients = ConcurrentHashMap<String, PgmqClient>()
    private val incomingRunning = ConcurrentHashMap<String, AtomicBoolean>()

    @PostConstruct
    fun init() {
        logger.info { "PGMQ Connector initialized" }
    }

    /**
     * Handle Quarkus shutdown event - this is called early in the shutdown process.
     */
    fun onQuarkusShutdown(@Observes event: ShutdownEvent) {
        logger.info { "PGMQ Connector received shutdown event" }
        isShutdown.set(true)
        waitForPendingMessages()
        stopAllIncoming()
    }

    @PreDestroy
    fun destroy() {
        waitForPendingMessages()
        stopAllOutgoing()
        stopAllIncoming()
        scope.cancel()
        logger.info { "PGMQ Connector destroyed" }
    }

    // ========== Outgoing (Producer) Implementation ==========

    override fun getSubscriber(cfg: Config): Flow.Subscriber<out Message<*>> {
        val channelName = cfg.getValue("channel-name", String::class.java)
        val connectorConfig = PgmqConnectorConfig(config, channelName)

        logger.info { "Creating PGMQ outgoing channel: $channelName for queue: ${connectorConfig.queue}" }

        val client = PgmqClient(connectorConfig)
        outgoingClients[channelName] = client

        // Initialize the client synchronously to ensure it's ready before accepting messages
        runBlocking {
            try {
                client.initialize()
                logger.info { "PGMQ outgoing channel $channelName initialized" }
            } catch (t: Throwable) {
                logger.error(t) { "Failed to initialize PGMQ outgoing channel: $channelName" }
            }
        }

        val subscriber = PgmqSubscriber(channelName, client, connectorConfig, scope, isShutdown)
        outgoingSubscribers[channelName] = subscriber
        return subscriber
    }

    private fun waitForPendingMessages() {
        val totalPending = outgoingSubscribers.values.sumOf { it.pendingCount }
        if (totalPending > 0) {
            logger.info { "⏳ Waiting for $totalPending pending messages to be sent" }
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    outgoingSubscribers.values.forEach { it.awaitPendingMessages() }
                } ?: logger.warn { "⚠️ Timeout waiting for pending messages" }
            }
            logger.info { "✅ Pending messages processed" }
        }
    }

    private fun stopOutgoing(channelName: String) {
        outgoingSubscribers.remove(channelName)?.cancel()
        outgoingClients.remove(channelName)?.close()
        logger.info { "Stopped PGMQ outgoing channel: $channelName" }
    }

    private fun stopAllOutgoing() {
        outgoingSubscribers.keys.toList().forEach { stopOutgoing(it) }
    }

    // ========== Incoming (Consumer) Implementation ==========

    override fun getPublisher(cfg: Config): Flow.Publisher<out Message<*>> {
        val channelName = cfg.getValue("channel-name", String::class.java)
        val connectorConfig = PgmqConnectorConfig(config, channelName)

        logger.info { "Creating PGMQ incoming channel: $channelName for queue: ${connectorConfig.queue}" }

        val client = PgmqClient(connectorConfig)
        incomingClients[channelName] = client
        incomingRunning[channelName] = AtomicBoolean(true)

        // Create polling flow using Kotlin coroutines, convert to Publisher only at the end
        val reactivePublisher = createPollingFlow(channelName, client, connectorConfig)
            .onStart { client.initialize() }
            .map { pgmqMessage -> createMessage(channelName, client, connectorConfig, pgmqMessage) }
            .catch { t ->
                logger.error(t) { "Failed in PGMQ incoming channel: $channelName" }
                delay(connectorConfig.pollInterval.toMillis())
                createPollingFlow(channelName, client, connectorConfig)
                    .map { pgmqMessage -> createMessage(channelName, client, connectorConfig, pgmqMessage) }
                    .collect { emit(it) }
            }
            .asPublisher(scope.coroutineContext)

        return AdaptersToFlow.publisher(reactivePublisher)
    }

    private fun createPollingFlow(
        channelName: String,
        client: PgmqClient,
        config: PgmqConnectorConfig
    ) = flow {
        val maxPollSeconds = (config.pollInterval.toMillis() / 1000).toInt().coerceAtLeast(1)

        while (incomingRunning[channelName]?.get() == true) {
            try {
                val messages = client.readWithPoll(
                    batchSize = config.batchSize,
                    maxPollSeconds = maxPollSeconds,
                    pollIntervalMs = 100
                )
                messages.forEach { emit(it) }
            } catch (t: Throwable) {
                val isCancellation = t is kotlinx.coroutines.CancellationException ||
                    t.cause is kotlinx.coroutines.CancellationException ||
                    t.message?.contains("cancelled", ignoreCase = true) == true

                val isShuttingDown = isShutdown.get() ||
                    incomingRunning[channelName]?.get() != true ||
                    isCancellation

                if (!isShuttingDown) {
                    logger.error(t) { "Error polling PGMQ queue ${config.queue}" }
                    delay(1000)
                } else {
                    logger.debug { "PGMQ polling stopped for queue ${config.queue} (shutdown)" }
                    break
                }
            }
        }
    }

    private fun createMessage(
        channelName: String,
        client: PgmqClient,
        config: PgmqConnectorConfig,
        pgmqMessage: PgmqMessage
    ): Message<String> {
        val metadata = PgmqIncomingMetadata(
            msgId = pgmqMessage.msgId,
            readCount = pgmqMessage.readCt,
            enqueuedAt = pgmqMessage.enqueuedAt,
            visibilityTimeout = pgmqMessage.vt,
            queue = config.queue,
            headers = pgmqMessage.headers
        )

        return Message.of(pgmqMessage.message, org.eclipse.microprofile.reactive.messaging.Metadata.of(metadata))
            .withAck {
                scope.future {
                    client.delete(pgmqMessage.msgId)
                    logger.debug { "Acknowledged message ${pgmqMessage.msgId} from queue ${config.queue}" }
                }.thenApply { null as Void? }
            }
            .withNack { reason ->
                handleNack(client, config, pgmqMessage, reason)
            }
    }

    private fun handleNack(
        client: PgmqClient,
        config: PgmqConnectorConfig,
        message: PgmqMessage,
        reason: Throwable?
    ): CompletionStage<Void> {
        return if (message.readCt >= config.maxRetries) {
            val errorMessage = reason?.message ?: "Unknown error"
            scope.future {
                client.moveToDeadLetterQueue(message.msgId, message.message, errorMessage)
                logger.warn { "Message ${message.msgId} exceeded max retries, moved to DLQ" }
            }.thenApply { null as Void? }
        } else {
            logger.debug { "Nacked message ${message.msgId}, will be redelivered (attempt ${message.readCt}/${config.maxRetries})" }
            CompletableFuture.completedFuture(null)
        }
    }

    private fun stopIncoming(channelName: String) {
        incomingRunning[channelName]?.set(false)
        incomingClients.remove(channelName)?.close()
        logger.info { "Stopped PGMQ incoming channel: $channelName" }
    }

    private fun stopAllIncoming() {
        incomingRunning.keys.forEach { stopIncoming(it) }
    }

    // ========== Inner Classes ==========

    /**
     * Subscriber that sends messages to PGMQ.
     */
    private class PgmqSubscriber(
        private val channelName: String,
        private val client: PgmqClient,
        private val config: PgmqConnectorConfig,
        private val scope: CoroutineScope,
        private val isShutdown: AtomicBoolean,
    ) : Flow.Subscriber<Message<*>> {

        private val running = AtomicBoolean(true)
        private var subscription: Flow.Subscription? = null
        private val pending = AtomicInteger(0)

        val pendingCount: Int get() = pending.get()

        override fun onSubscribe(s: Flow.Subscription) {
            this.subscription = s
            s.request(1)
        }

        override fun onNext(message: Message<*>) {
            if (!running.get()) {
                message.nack(IllegalStateException("Channel $channelName is stopped"))
                return
            }

            val payload = when (val p = message.payload) {
                is String -> p
                is ByteArray -> String(p, Charsets.UTF_8)
                else -> p.toString()
            }

            val pgmqMetadata = message.metadata.firstOrNull { it is PgmqOutgoingMetadata }
                as? PgmqOutgoingMetadata

            val delaySeconds = pgmqMetadata?.delaySeconds ?: 0
            val headersJson = pgmqMetadata?.messageId?.let { messageId ->
                """{"messageId":"$messageId"}"""
            }

            pending.incrementAndGet()

            scope.launch {
                try {
                    val msgId = client.send(payload, delaySeconds, headersJson)
                    logger.debug { "Sent message $msgId to queue ${config.queue}" }
                    message.ack().whenComplete { _, error ->
                        if (error != null) {
                            logger.error(error) { "Failed to ack message after send" }
                        }
                        messageDone()
                    }
                } catch (error: Throwable) {
                    if (error.message?.contains("unique constraint") == true ||
                        error.message?.contains("duplicate key") == true
                    ) {
                        logger.debug { "Duplicate message detected (idempotent send), treating as success" }
                        message.ack().whenComplete { _, _ -> messageDone() }
                    } else {
                        logger.error(error) { "Failed to send message to queue ${config.queue}" }
                        message.nack(error).whenComplete { _, _ -> messageDone() }
                    }
                }
            }
        }

        private fun messageDone() {
            pending.decrementAndGet()
            if (!isShutdown.get()) {
                subscription?.request(1)
            }
        }

        suspend fun awaitPendingMessages() {
            while (pending.get() > 0) {
                delay(10)
            }
        }

        override fun onError(t: Throwable) {
            logger.error(t) { "Error in PGMQ outgoing channel $channelName" }
            running.set(false)
        }

        override fun onComplete() {
            logger.info { "PGMQ outgoing channel $channelName completed" }
            running.set(false)
        }

        fun cancel() {
            running.set(false)
            subscription?.cancel()
        }
    }
}
