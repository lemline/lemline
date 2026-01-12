// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.connector

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.postgres.PgmqClient
import com.lemline.runner.messaging.postgres.config.PgmqConnectorConfig
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import org.eclipse.microprofile.reactive.messaging.spi.OutgoingConnectorFactory
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * SmallRye Reactive Messaging outgoing connector for PGMQ.
 *
 * This connector publishes messages to a PostgreSQL-based message queue using the PGMQ pattern.
 *
 * Configuration properties (prefixed with mp.messaging.outgoing.{channel}):
 * - host: PostgreSQL host
 * - port: PostgreSQL port
 * - database: Database name
 * - username: Database username
 * - password: Database password
 * - queue: PGMQ queue name
 *
 * @see PgmqConnectorConfig for all configuration options
 */
@ApplicationScoped
@Connector(PgmqConnectorConfig.CONNECTOR_NAME)
class PgmqOutgoingConnector : OutgoingConnectorFactory {

    companion object {
        private val logger = logger()
    }

    @Inject
    lateinit var config: Config

    @Inject
    lateinit var executionHolder: ExecutionHolder

    private val clients = ConcurrentHashMap<String, PgmqClient>()
    private val subscribers = ConcurrentHashMap<String, PgmqSubscriber>()

    @PostConstruct
    fun init() {
        logger.info { "PGMQ Outgoing Connector initialized" }
    }

    @PreDestroy
    fun destroy() {
        stopAll()
    }

    override fun getSubscriber(cfg: org.eclipse.microprofile.reactive.messaging.spi.ConnectorConfig): Subscriber<out Message<*>> {
        val channelName = cfg.getValue("channel-name", String::class.java)
        val connectorConfig = PgmqConnectorConfig(config, channelName)

        logger.info { "Creating PGMQ outgoing channel: $channelName for queue: ${connectorConfig.queue}" }

        val client = PgmqClient(connectorConfig)
        clients[channelName] = client

        // Initialize the client asynchronously
        Uni.createFrom().completionStage {
            future(Dispatchers.IO) {
                client.initialize()
            }
        }.subscribe().with(
            { logger.info { "PGMQ outgoing channel $channelName initialized" } },
            { t -> logger.error(t) { "Failed to initialize PGMQ outgoing channel: $channelName" } }
        )

        val subscriber = PgmqSubscriber(channelName, client, connectorConfig)
        subscribers[channelName] = subscriber
        return subscriber
    }

    fun stop(channelName: String) {
        subscribers.remove(channelName)?.cancel()
        clients.remove(channelName)?.close()
        logger.info { "Stopped PGMQ outgoing channel: $channelName" }
    }

    fun stopAll() {
        subscribers.keys.toList().forEach { stop(it) }
    }

    /**
     * Subscriber that sends messages to PGMQ.
     */
    private class PgmqSubscriber(
        private val channelName: String,
        private val client: PgmqClient,
        private val config: PgmqConnectorConfig,
    ) : Subscriber<Message<*>> {

        private val running = AtomicBoolean(true)
        private val requested = AtomicLong(0)
        private var subscription: Subscription? = null

        override fun onSubscribe(s: Subscription) {
            this.subscription = s
            // Request messages one at a time for backpressure control
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

            // Extract delay from metadata if present
            val delay = message.metadata.firstOrNull { it is PgmqOutgoingMetadata }
                ?.let { (it as PgmqOutgoingMetadata).delaySeconds }
                ?: 0

            // Send the message
            Uni.createFrom().completionStage {
                future(Dispatchers.IO) {
                    client.send(payload, delay)
                }
            }
                .subscribe().with(
                    { msgId ->
                        logger.debug { "Sent message $msgId to queue ${config.queue}" }
                        message.ack()
                            .whenComplete { _, error ->
                                if (error != null) {
                                    logger.error(error) { "Failed to ack message after send" }
                                }
                                // Request next message
                                subscription?.request(1)
                            }
                    },
                    { error ->
                        logger.error(error) { "Failed to send message to queue ${config.queue}" }
                        message.nack(error)
                            .whenComplete { _, _ ->
                                // Request next message even on failure
                                subscription?.request(1)
                            }
                    }
                )
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
