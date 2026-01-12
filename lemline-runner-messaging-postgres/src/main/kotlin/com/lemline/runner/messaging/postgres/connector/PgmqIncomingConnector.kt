// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.connector

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.postgres.PgmqClient
import com.lemline.runner.messaging.postgres.PgmqMessage
import com.lemline.runner.messaging.postgres.config.PgmqConnectorConfig
import io.smallrye.reactive.messaging.connector.InboundConnector
import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
import kotlinx.coroutines.reactive.asPublisher
import mutiny.zero.flow.adapters.AdaptersToFlow
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SmallRye Reactive Messaging incoming connector for PGMQ.
 *
 * This connector consumes messages from a PostgreSQL-based message queue using the PGMQ pattern.
 * It polls the queue at regular intervals and provides messages to reactive streams.
 *
 * Configuration properties (prefixed with mp.messaging.incoming.{channel}):
 * - host: PostgreSQL host
 * - port: PostgreSQL port
 * - database: Database name
 * - username: Database username
 * - password: Database password
 * - queue: PGMQ queue name
 * - visibility-timeout: Seconds before unacknowledged message becomes visible again
 * - poll-interval: Milliseconds between polls
 * - batch-size: Messages to fetch per poll
 *
 * @see PgmqConnectorConfig for all configuration options
 */
@ApplicationScoped
@Connector(PgmqConnectorConfig.CONNECTOR_NAME)
class PgmqIncomingConnector : InboundConnector {

    companion object {
        private val logger = logger()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var executionHolder: ExecutionHolder

    private val clients = ConcurrentHashMap<String, PgmqClient>()
    private val running = ConcurrentHashMap<String, AtomicBoolean>()

    @PostConstruct
    fun init() {
        logger.info { "PGMQ Incoming Connector initialized" }
    }

    @PreDestroy
    fun destroy() {
        stopAll()
        scope.cancel()
        logger.info { "PGMQ Incoming Connector destroyed" }
    }

    override fun getPublisher(cfg: Config): Flow.Publisher<out Message<*>> {
        val channelName = cfg.getValue("channel-name", String::class.java)
        val connectorConfig = PgmqConnectorConfig(config, channelName)

        logger.info { "Creating PGMQ incoming channel: $channelName for queue: ${connectorConfig.queue}" }

        val client = PgmqClient(connectorConfig)
        clients[channelName] = client
        running[channelName] = AtomicBoolean(true)

        // Create polling flow using Kotlin coroutines, convert to Publisher only at the end
        val reactivePublisher = createPollingFlow(channelName, client, connectorConfig)
            .onStart { client.initialize() }
            .map { pgmqMessage -> createMessage(channelName, client, connectorConfig, pgmqMessage) }
            .catch { t ->
                logger.error(t) { "Failed in PGMQ incoming channel: $channelName" }
                // On failure, wait and retry by re-emitting from a new polling flow
                delay(connectorConfig.pollInterval.toMillis())
                createPollingFlow(channelName, client, connectorConfig)
                    .map { pgmqMessage -> createMessage(channelName, client, connectorConfig, pgmqMessage) }
                    .collect { emit(it) }
            }
            .asPublisher(scope.coroutineContext)

        // Convert Reactive Streams Publisher to JDK Flow Publisher
        return AdaptersToFlow.publisher(reactivePublisher)
    }

    private fun createPollingFlow(
        channelName: String,
        client: PgmqClient,
        config: PgmqConnectorConfig
    ) = flow {
        while (running[channelName]?.get() == true) {
            try {
                val messages = client.read(config.batchSize)
                messages.forEach { emit(it) }
            } catch (t: Throwable) {
                logger.error(t) { "Error polling PGMQ queue ${config.queue}" }
            }
            delay(config.pollInterval.toMillis())
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
            queue = config.queue
        )

        return Message.of(pgmqMessage.message, PgmqMetadataContainer(metadata))
            .withAck {
                // Acknowledge: delete the message
                scope.future {
                    client.delete(pgmqMessage.msgId)
                    logger.debug { "Acknowledged message ${pgmqMessage.msgId} from queue ${config.queue}" }
                }.thenApply { null as Void? }
            }
            .withNack { reason ->
                // Negative acknowledge: handle based on retry count
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
            // Max retries exceeded, move to DLQ
            val errorMessage = reason?.message ?: "Unknown error"
            scope.future {
                client.moveToDeadLetterQueue(message.msgId, message.message, errorMessage)
                logger.warn { "Message ${message.msgId} exceeded max retries, moved to DLQ" }
            }.thenApply { null as Void? }
        } else {
            // Message will become visible again after visibility timeout
            // Just log the nack, the message will be redelivered automatically
            logger.debug { "Nacked message ${message.msgId}, will be redelivered (attempt ${message.readCt}/${config.maxRetries})" }
            CompletableFuture.completedFuture(null)
        }
    }

    fun stop(channelName: String) {
        running[channelName]?.set(false)
        clients.remove(channelName)?.close()
        logger.info { "Stopped PGMQ incoming channel: $channelName" }
    }

    fun stopAll() {
        running.keys.forEach { stop(it) }
    }
}

/**
 * Container for PGMQ metadata to be attached to messages.
 */
data class PgmqMetadataContainer(val metadata: PgmqIncomingMetadata) : Iterable<Any> {
    override fun iterator(): Iterator<Any> = listOf(metadata).iterator()
}
