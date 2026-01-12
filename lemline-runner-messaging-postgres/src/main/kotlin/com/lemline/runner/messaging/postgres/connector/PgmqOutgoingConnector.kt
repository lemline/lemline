// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.connector

import com.lemline.common.logger.logger
import com.lemline.runner.messaging.postgres.PgmqClient
import com.lemline.runner.messaging.postgres.config.PgmqConnectorConfig
import io.smallrye.reactive.messaging.connector.OutboundConnector
import io.smallrye.reactive.messaging.providers.connectors.ExecutionHolder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.reactive.messaging.Message
import org.eclipse.microprofile.reactive.messaging.spi.Connector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean

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
class PgmqOutgoingConnector : OutboundConnector {

    companion object {
        private val logger = logger()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        scope.cancel()
        logger.info { "PGMQ Outgoing Connector destroyed" }
    }

    override fun getSubscriber(cfg: Config): Flow.Subscriber<out Message<*>> {
        val channelName = cfg.getValue("channel-name", String::class.java)
        val connectorConfig = PgmqConnectorConfig(config, channelName)

        logger.info { "Creating PGMQ outgoing channel: $channelName for queue: ${connectorConfig.queue}" }

        val client = PgmqClient(connectorConfig)
        clients[channelName] = client

        // Initialize the client asynchronously using coroutines
        scope.launch {
            try {
                client.initialize()
                logger.info { "PGMQ outgoing channel $channelName initialized" }
            } catch (t: Throwable) {
                logger.error(t) { "Failed to initialize PGMQ outgoing channel: $channelName" }
            }
        }

        val subscriber = PgmqSubscriber(channelName, client, connectorConfig, scope)
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
        private val scope: CoroutineScope,
    ) : Flow.Subscriber<Message<*>> {

        private val running = AtomicBoolean(true)
        private var subscription: Flow.Subscription? = null

        override fun onSubscribe(s: Flow.Subscription) {
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
            val delaySeconds = message.metadata.firstOrNull { it is PgmqOutgoingMetadata }
                ?.let { (it as PgmqOutgoingMetadata).delaySeconds }
                ?: 0

            // Send the message using coroutines
            scope.launch {
                try {
                    val msgId = client.send(payload, delaySeconds)
                    logger.debug { "Sent message $msgId to queue ${config.queue}" }
                    message.ack().whenComplete { _, error ->
                        if (error != null) {
                            logger.error(error) { "Failed to ack message after send" }
                        }
                        // Request next message
                        subscription?.request(1)
                    }
                } catch (error: Throwable) {
                    logger.error(error) { "Failed to send message to queue ${config.queue}" }
                    message.nack(error).whenComplete { _, _ ->
                        // Request next message even on failure
                        subscription?.request(1)
                    }
                }
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
