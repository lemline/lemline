// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.connector

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.postgres.PgmqClient
import com.lemline.runner.messaging.postgres.PgmqMessage
import com.lemline.runner.messaging.postgres.config.PgmqConnectorConfig
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.eclipse.microprofile.reactive.messaging.spi.IncomingConnectorFactory
import org.reactivestreams.Publisher
import java.util.concurrent.ConcurrentHashMap
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
class PgmqIncomingConnector : IncomingConnectorFactory {

    companion object {
        private val logger = logger()
    }

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

    override fun getPublisher(cfg: org.eclipse.microprofile.reactive.messaging.spi.ConnectorConfig): Publisher<out Message<*>> {
        val channelName = cfg.getValue("channel-name", String::class.java)
        val connectorConfig = PgmqConnectorConfig(config, channelName)

        logger.info { "Creating PGMQ incoming channel: $channelName for queue: ${connectorConfig.queue}" }

        val client = PgmqClient(connectorConfig)
        clients[channelName] = client
        running[channelName] = AtomicBoolean(true)

        // Initialize the client (create extension and queue)
        val initUni = Uni.createFrom().item { }
            .chain { _ ->
                Uni.createFrom().completionStage {
                    kotlinx.coroutines.future.future(kotlinx.coroutines.Dispatchers.IO) {
                        client.initialize()
                    }
                }
            }

        // Create a polling multi that fetches messages
        return initUni
            .onItem().transformToMulti { _ ->
                createPollingMulti(channelName, client, connectorConfig)
            }
            .onFailure().invoke { t ->
                logger.error(t) { "Failed to initialize PGMQ incoming channel: $channelName" }
            }
    }

    private fun createPollingMulti(
        channelName: String,
        client: PgmqClient,
        config: PgmqConnectorConfig
    ): Multi<Message<String>> {
        return Multi.createFrom().ticks().every(config.pollInterval)
            .onItem().transformToMultiAndConcatenate { _ ->
                if (!running[channelName]?.get()!!) {
                    return@transformToMultiAndConcatenate Multi.createFrom().empty()
                }

                client.readReactive(config.batchSize)
                    .onItem().transformToMulti { messages ->
                        Multi.createFrom().iterable(messages)
                    }
                    .map { pgmqMessage ->
                        createMessage(channelName, client, config, pgmqMessage)
                    }
            }
            .onFailure().invoke { t ->
                logger.error(t) { "Error polling PGMQ queue ${config.queue}" }
            }
            .onFailure().recoverWithMulti { _ ->
                // On failure, wait and retry
                Multi.createFrom().ticks().every(config.pollInterval)
                    .skip().first()
                    .onItem().transformToMultiAndConcatenate { _ ->
                        createPollingMulti(channelName, client, config)
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
            queue = config.queue
        )

        return Message.of(pgmqMessage.message, PgmqMetadataContainer(metadata))
            .withAck {
                // Acknowledge: delete the message
                client.deleteReactive(pgmqMessage.msgId)
                    .replaceWithVoid()
                    .invoke { _ ->
                        logger.debug { "Acknowledged message ${pgmqMessage.msgId} from queue ${config.queue}" }
                    }
                    .subscribeAsCompletionStage()
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
    ): java.util.concurrent.CompletionStage<Void> {
        val errorMessage = reason?.message ?: "Unknown error"

        return if (message.readCt >= config.maxRetries) {
            // Max retries exceeded, move to DLQ
            Uni.createFrom().completionStage {
                kotlinx.coroutines.future.future(kotlinx.coroutines.Dispatchers.IO) {
                    client.moveToDeadLetterQueue(message.msgId, message.message, errorMessage)
                }
            }
                .replaceWithVoid()
                .invoke { _ ->
                    logger.warn { "Message ${message.msgId} exceeded max retries, moved to DLQ" }
                }
                .subscribeAsCompletionStage()
        } else {
            // Message will become visible again after visibility timeout
            // Just log the nack, the message will be redelivered automatically
            Uni.createFrom().voidItem()
                .invoke { _ ->
                    logger.debug { "Nacked message ${message.msgId}, will be redelivered (attempt ${message.readCt}/${config.maxRetries})" }
                }
                .subscribeAsCompletionStage()
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
class PgmqMetadataContainer(val metadata: PgmqIncomingMetadata) : Iterable<Any> {
    override fun iterator(): Iterator<Any> = listOf(metadata).iterator()
}
