// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.rabbitmq

import com.lemline.common.logger.logger
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.common.namespaces.destinationFor
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_HOST_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PASSWORD_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_PORT_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_USER_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
import com.lemline.runner.config.LemlineConfiguration
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Declares the broker-side wiring of the RabbitMQ per-namespace destination model at startup.
 *
 * The SmallRye connector configuration (see LemlineConfigSource) only declares the two ends:
 * - producers publish to a per-channel topic exchange `<prefix>-<type>` (e.g. `lemline-commands`)
 *   with a per-message routing key equal to the message's workflow namespace;
 * - consumers read a per-pool durable queue `<prefix>-<type>.<sorted-namespaces-dot-joined>`
 *   (e.g. `lemline-commands.acme.team`), declared by the incoming connector.
 *
 * This bean declares the middle, for each enabled channel family (commands, events, cloudevents)
 * and each followed namespace:
 * 1. a durable topic exchange per namespace: `<prefix>-<namespace>-<type>` (e.g.
 *    `lemline-acme-commands`) — the ACL-bearing, per-namespace named resource;
 * 2. an exchange-to-exchange binding from the channel exchange to the namespace exchange with
 *    routing key = the namespace: `lemline-commands --acme--> lemline-acme-commands`;
 * 3. (consumer side only) a queue binding from the namespace exchange to the pool queue with
 *    routing key `#`: `lemline-acme-commands --#--> lemline-commands.acme.team`.
 *
 * Steps 1 and 2 run synchronously in the startup observer so producers have a complete route
 * even before any consumer pool starts. Step 3 races the incoming connector, which declares the
 * pool queue asynchronously: it is retried in a background coroutine (passive queue declare every
 * [QUEUE_BIND_RETRY_DELAY] for up to [QUEUE_BIND_TIMEOUT]) and logs an explicit WARN if the queue
 * never appears. The AMQP connection is closed as soon as all bindings are done.
 *
 * No-op unless `lemline.messaging.type` is `rabbitmq`.
 */
@ApplicationScoped
class RabbitTopologyBootstrap(
    private val config: LemlineConfiguration,
) {
    private val logger = logger()

    /** Scope of the queue-binding retry loop; cancelled on shutdown. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Per-channel-family topology to declare on the broker. */
    internal data class ChannelTopology(
        val type: DestinationType,
        /** Channel exchange producers publish to: `<prefix>-<type>`. */
        val channelExchange: String,
        /** Namespace -> per-namespace exchange `<prefix>-<namespace>-<type>`. */
        val namespaceExchanges: Map<String, String>,
        /** Whether this deployment consumes the channel (requires the pool queue binding). */
        val consumerEnabled: Boolean,
        /** Pool queue the consumer reads from: `<prefix>-<type>.<sorted-namespaces-dot-joined>`. */
        val poolQueue: String,
    )

    @Suppress("unused")
    fun onStart(@Observes event: StartupEvent) {
        if (MessagingType.fromConfigValue(config.messaging().type()) != MessagingType.RABBITMQ) return

        val plans = channelTopologies()
        if (plans.isEmpty()) {
            logger.debug { "RabbitMQ namespace topology: no enabled channel family, nothing to declare" }
            return
        }

        val connection = newConnection()
        var handedOffToBindingLoop = false
        try {
            // 1 + 2: exchanges and exchange-to-exchange bindings, synchronously (fast, idempotent)
            connection.createChannel().use { channel ->
                plans.forEach { declareNamespaceExchanges(channel, it) }
            }

            // 3: pool queue bindings — the queue is declared asynchronously by the incoming
            // connector, so retry in the background instead of blocking the startup thread
            val consumerPlans = plans.filter { it.consumerEnabled }
            if (consumerPlans.isNotEmpty()) {
                handedOffToBindingLoop = true
                scope.launch { bindPoolQueues(connection, consumerPlans) }
            }
        } finally {
            // The binding loop owns (and closes) the connection once launched
            if (!handedOffToBindingLoop) closeQuietly(connection)
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    /**
     * Derives the topology of each enabled channel family from the Lemline configuration,
     * matching the naming conventions applied by LemlineConfigSource.
     */
    private fun channelTopologies(): List<ChannelTopology> {
        val messaging = config.messaging()
        val prefix = messaging.prefix()
        val namespaces = followedNamespaces()
        val rabbit = messaging.rabbitmq().orElse(null)

        fun topology(
            type: DestinationType,
            producerEnabled: Boolean,
            consumerEnabled: Boolean,
            poolQueueOverride: String?,
        ): ChannelTopology? {
            if (!producerEnabled && !consumerEnabled) return null
            return ChannelTopology(
                type = type,
                channelExchange = RabbitTopologyNames.channelExchange(prefix, type),
                namespaceExchanges = namespaces.associateWith {
                    RabbitTopologyNames.namespaceExchange(prefix, it, type)
                },
                consumerEnabled = consumerEnabled,
                poolQueue = poolQueueOverride ?: RabbitTopologyNames.poolQueue(prefix, type, namespaces),
            )
        }

        val commands = messaging.commands().orElse(null)
        val events = messaging.events().orElse(null)
        val cloudevents = messaging.cloudevents().orElse(null)

        return listOfNotNull(
            topology(
                DestinationType.COMMANDS,
                producerEnabled = commands?.producer()?.enabled() == true,
                consumerEnabled = commands?.consumer()?.enabled() == true,
                poolQueueOverride = rabbit?.commands()?.queue()?.orElse(null),
            ),
            topology(
                DestinationType.EVENTS,
                producerEnabled = events?.producer()?.enabled() == true,
                consumerEnabled = events?.consumer()?.enabled() == true,
                poolQueueOverride = rabbit?.events()?.queue()?.orElse(null),
            ),
            topology(
                DestinationType.CLOUDEVENTS,
                producerEnabled = cloudevents?.producer()?.enabled() == true,
                consumerEnabled = cloudevents?.consumer()?.enabled() == true,
                poolQueueOverride = rabbit?.cloudevents()?.flatMap { it.queue() }?.orElse(null),
            ),
        )
    }

    /** Namespaces this process follows (`lemline.namespaces`), falling back to the default namespace. */
    private fun followedNamespaces(): List<String> =
        config.namespaces()
            .map { list -> list.map(String::trim).filter(String::isNotEmpty) }
            .filter { it.isNotEmpty() }
            .orElse(listOf(NamespaceScope.DEFAULT_NAMESPACE))

    /**
     * Declares the channel exchange (idempotent re-declaration with the exact arguments used by
     * the outgoing connector: topic, durable, no auto-delete — so bootstrap order does not
     * matter), one topic exchange per namespace, and the exchange-to-exchange bindings.
     */
    private fun declareNamespaceExchanges(channel: Channel, topology: ChannelTopology) {
        channel.exchangeDeclare(topology.channelExchange, BuiltinExchangeType.TOPIC, true)
        topology.namespaceExchanges.forEach { (namespace, namespaceExchange) ->
            channel.exchangeDeclare(namespaceExchange, BuiltinExchangeType.TOPIC, true)
            // <channelExchange> --<namespace>--> <namespaceExchange>
            channel.exchangeBind(namespaceExchange, topology.channelExchange, namespace)
        }
        logger.info {
            "RabbitMQ namespace topology: declared ${topology.namespaceExchanges.values} " +
                "fed by exchange '${topology.channelExchange}'"
        }
    }

    /**
     * Binds each pool queue to the namespace exchanges of its channel family with routing key `#`.
     *
     * The pool queue is declared asynchronously by the incoming connector, so its existence is
     * polled (passive declare) every [QUEUE_BIND_RETRY_DELAY] for up to [QUEUE_BIND_TIMEOUT];
     * an explicit WARN is logged for any queue that never appears. Closes [connection] when done.
     */
    private suspend fun bindPoolQueues(connection: Connection, plans: List<ChannelTopology>) {
        try {
            val pending = plans.toMutableList()
            val deadline = System.nanoTime() + QUEUE_BIND_TIMEOUT.inWholeNanoseconds
            while (true) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val topology = iterator.next()
                    if (queueExists(connection, topology.poolQueue)) {
                        bindPoolQueue(connection, topology)
                        iterator.remove()
                    }
                }
                if (pending.isEmpty() || System.nanoTime() >= deadline) break
                delay(QUEUE_BIND_RETRY_DELAY)
            }
            pending.forEach { topology ->
                logger.warn {
                    "RabbitMQ namespace topology: pool queue '${topology.poolQueue}' " +
                        "(${topology.type.suffix} channel) did not appear within $QUEUE_BIND_TIMEOUT — " +
                        "it was NOT bound to ${topology.namespaceExchanges.values} and the consumer " +
                        "will not receive any message until the binding is created"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "RabbitMQ namespace topology: pool queue binding failed" }
        } finally {
            closeQuietly(connection)
        }
    }

    private fun bindPoolQueue(connection: Connection, topology: ChannelTopology) {
        connection.createChannel().use { channel ->
            topology.namespaceExchanges.values.forEach { namespaceExchange ->
                // <namespaceExchange> --#--> <poolQueue>
                channel.queueBind(topology.poolQueue, namespaceExchange, MATCH_ALL_ROUTING_KEY)
            }
        }
        logger.info {
            "RabbitMQ namespace topology: bound pool queue '${topology.poolQueue}' " +
                "to ${topology.namespaceExchanges.values}"
        }
    }

    /**
     * Checks queue existence with a passive declare. A failed passive declare closes the AMQP
     * channel (that is protocol behavior), hence the fresh channel per attempt.
     */
    private fun queueExists(connection: Connection, queue: String): Boolean {
        val channel = connection.createChannel()
        return try {
            channel.queueDeclarePassive(queue)
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { if (channel.isOpen) channel.close() }
        }
    }

    /**
     * Opens a plain AMQP connection using the same settings and defaults as LemlineConfigSource
     * applies to the SmallRye connector (localhost / 5672 / guest / guest / '/').
     */
    private fun newConnection(): Connection {
        val rabbit = config.messaging().rabbitmq().orElse(null)
        val factory = ConnectionFactory().apply {
            host = rabbit?.hostname()?.orElse(null) ?: RABBITMQ_HOST_DEFAULT
            port = rabbit?.port()?.orElse(null) ?: RABBITMQ_PORT_DEFAULT.toInt()
            username = rabbit?.username()?.orElse(null) ?: RABBITMQ_USER_DEFAULT
            password = rabbit?.password()?.orElse(null) ?: RABBITMQ_PASSWORD_DEFAULT
            virtualHost = rabbit?.virtualHost()?.orElse(null) ?: RABBITMQ_VHOST_DEFAULT
            if (rabbit?.sslEnabled()?.orElse(false) == true) useSslProtocol()
            connectionTimeout = CONNECTION_TIMEOUT_MS
        }
        return factory.newConnection(CONNECTION_NAME)
    }

    private fun closeQuietly(connection: Connection) {
        runCatching { connection.close() }
    }

    internal companion object {
        /** Interval between two passive-declare probes of a pending pool queue. */
        val QUEUE_BIND_RETRY_DELAY: Duration = 500.milliseconds

        /** Give-up delay for the pool queue binding loop. */
        val QUEUE_BIND_TIMEOUT: Duration = 60.seconds

        const val CONNECTION_TIMEOUT_MS = 5_000
        const val CONNECTION_NAME = "lemline-rabbit-topology-bootstrap"
        const val MATCH_ALL_ROUTING_KEY = "#"
    }
}

/**
 * Naming conventions of the RabbitMQ per-namespace destination model.
 * Must stay in sync with LemlineConfigSource (channel exchange and pool queue derivation).
 */
internal object RabbitTopologyNames {

    /** Channel exchange producers publish to: `<prefix>-<type>` (e.g. `lemline-commands`). */
    fun channelExchange(prefix: String, type: DestinationType): String = "$prefix-${type.suffix}"

    /** Per-namespace exchange: `<prefix>-<namespace>-<type>` (e.g. `lemline-acme-commands`). */
    fun namespaceExchange(prefix: String, namespace: String, type: DestinationType): String =
        destinationFor(prefix, namespace, type)

    /** Deterministic pool queue name: `<channel-exchange>.<sorted-namespaces-dot-joined>`. */
    fun poolQueue(prefix: String, type: DestinationType, namespaces: List<String>): String =
        "${channelExchange(prefix, type)}.${namespaces.sorted().joinToString(".")}"
}
