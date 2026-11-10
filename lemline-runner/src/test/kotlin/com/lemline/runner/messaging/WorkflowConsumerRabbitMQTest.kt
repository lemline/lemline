// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.destinationFor
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.tests.profiles.RabbitMQProfile
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.MessageProperties
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance


/**
 * Runs the WorkflowConsumerTest suite against a RabbitMQ broker.
 *
 * Destination model:
 * - The runner follows namespace `test-in` (see [RabbitMQProfile]) → it consumes the
 *   deterministic pool queues (`lemline-commands.test-in` / `lemline-events.test-in`),
 *   read from `mp.messaging.incoming.*.queue.name`. The test SENDS raw commands
 *   directly to that queue via the default exchange (routing key = queue name), which
 *   requires no bindings.
 * - Emissions are published to the channel topic exchanges (`lemline-commands` /
 *   `lemline-events`) with per-message routing key = the workflow's namespace (`test`).
 *   The test OBSERVES them by binding its own observation queues to those exchanges
 *   with routing key `test` — the runner does not consume these emissions.
 */
@QuarkusTest
@TestProfile(RabbitMQProfile::class)
@Tag("integration")
@RequiresDocker
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WorkflowConsumerRabbitMQTest : WorkflowConsumerTest() {

    /** Routing key carried by every runner emission: the workflow's namespace. */
    private val emissionRoutingKey = "test"

    private lateinit var commandsQueueIn: String
    private lateinit var eventsQueueIn: String
    private lateinit var commandsExchange: String
    private lateinit var eventsExchange: String

    /** Queue where this test observes the runner's command emissions. */
    private val commandsQueueObserved = destinationFor("lemline", "test", DestinationType.COMMANDS)

    /** Queue where this test observes the runner's event emissions. */
    private val eventsQueueObserved = destinationFor("lemline", "test", DestinationType.EVENTS)

    private lateinit var connection: Connection
    private lateinit var commandsChannel: Channel
    private lateinit var eventsChannel: Channel
    private val commandsDeliveries = LinkedBlockingQueue<Delivery>()
    private val eventsDeliveries = LinkedBlockingQueue<Delivery>()

    @BeforeAll
    fun initClients() {
        val config = ConfigProvider.getConfig()

        val rabbitmqHost = config.getValue("rabbitmq-host", String::class.java)
        val rabbitmqPort = config.getValue("rabbitmq-port", String::class.java)
        val rabbitmqUsername = config.getValue("rabbitmq-username", String::class.java)
        val rabbitmqPassword = config.getValue("rabbitmq-password", String::class.java)

        // Pool queues the runner consumes (derived from the followed namespaces)
        commandsQueueIn = config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.queue.name", String::class.java)
        eventsQueueIn = config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.queue.name", String::class.java)

        // Channel topic exchanges the producers publish to (static, per channel)
        commandsExchange =
            config.getValue("mp.messaging.outgoing.$COMMANDS_OUT_CHANNEL.exchange.name", String::class.java)
        eventsExchange =
            config.getValue("mp.messaging.outgoing.$EVENTS_OUT_CHANNEL.exchange.name", String::class.java)

        // In testing, the consumed queues and the observed queues must be different
        require(commandsQueueIn != commandsQueueObserved) {
            "For RabbitMQ *testing*, queues In ($commandsQueueIn) and Observed ($commandsQueueObserved) must be different"
        }
        require(eventsQueueIn != eventsQueueObserved) {
            "For RabbitMQ *testing*, queues In ($eventsQueueIn) and Observed ($eventsQueueObserved) must be different"
        }

        // Setup RabbitMQ connection
        val factory = ConnectionFactory().apply {
            host = rabbitmqHost
            port = rabbitmqPort.toInt()
            username = rabbitmqUsername
            password = rabbitmqPassword
        }
        connection = factory.newConnection()
        commandsChannel = connection.createChannel()
        eventsChannel = connection.createChannel()

        // Declare the incoming pool queues with DLQ settings matching LemlineConfigSource
        val commandsDlx =
            config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.dead-letter-exchange", String::class.java)
        val commandsDlq =
            config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.dead-letter-routing-key", String::class.java)
        val commandsArgs = mapOf(
            "x-dead-letter-exchange" to commandsDlx,
            "x-dead-letter-routing-key" to commandsDlq
        )
        commandsChannel.queueDeclare(commandsQueueIn, true, false, false, commandsArgs)

        val eventsDlx =
            config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.dead-letter-exchange", String::class.java)
        val eventsDlq =
            config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.dead-letter-routing-key", String::class.java)
        val eventsArgs = mapOf(
            "x-dead-letter-exchange" to eventsDlx,
            "x-dead-letter-routing-key" to eventsDlq
        )
        eventsChannel.queueDeclare(eventsQueueIn, true, false, false, eventsArgs)

        // Declare the channel topic exchanges the producers publish to.
        // The producer channels declare them at startup (topic, durable); declaring them
        // here too (idempotent, same args) avoids ordering races with app startup.
        commandsChannel.exchangeDeclare(commandsExchange, "topic", true)
        eventsChannel.exchangeDeclare(eventsExchange, "topic", true)

        // Declare the observation queues (where this test consumes)
        commandsChannel.queueDeclare(commandsQueueObserved, true, false, false, null)
        eventsChannel.queueDeclare(eventsQueueObserved, true, false, false, null)

        // Bind the observation queues to the channel exchanges with the emission
        // routing key (= the workflow namespace `test`)
        commandsChannel.queueBind(commandsQueueObserved, commandsExchange, emissionRoutingKey)
        eventsChannel.queueBind(eventsQueueObserved, eventsExchange, emissionRoutingKey)

        // Setup consumer callbacks
        val commandsCallback = DeliverCallback { _, delivery ->
            println("Received message on observed commands queue: ${String(delivery.body)}")
            commandsDeliveries.offer(delivery)
        }
        commandsChannel.basicConsume(commandsQueueObserved, true, commandsCallback) { }
        val eventsCallback = DeliverCallback { _, delivery ->
            println("Received message on observed events queue: ${String(delivery.body)}")
            eventsDeliveries.offer(delivery)
        }
        eventsChannel.basicConsume(eventsQueueObserved, true, eventsCallback) { }
    }

    @AfterAll
    fun closeClients() {
        if (::commandsChannel.isInitialized) commandsChannel.close()
        if (::eventsChannel.isInitialized) eventsChannel.close()
        if (::connection.isInitialized) connection.close()
    }

    override fun setupMessaging() {
        // Purge queues to ensure clean state between tests
        commandsChannel.queuePurge(commandsQueueIn)
        commandsChannel.queuePurge(commandsQueueObserved)
        eventsChannel.queuePurge(eventsQueueIn)
        eventsChannel.queuePurge(eventsQueueObserved)

        // Clear in-memory delivery queues
        commandsDeliveries.clear()
        eventsDeliveries.clear()
    }

    override fun cleanupMessaging() {
        // No-op - clients are closed in @AfterAll
    }

    override fun sendCommand(message: String) {
        // Publish directly to the runner's pool queue via the default exchange
        // (routing key = queue name) — requires no bindings
        commandsChannel.basicPublish(
            "",
            commandsQueueIn,
            MessageProperties.PERSISTENT_TEXT_PLAIN,
            message.toByteArray()
        )
    }

    override suspend fun receiveCommand(timeout: Long, unit: TimeUnit): String? =
        commandsDeliveries.poll(timeout, unit)?.let { String(it.body) }

    override suspend fun receiveEvent(timeout: Long, unit: TimeUnit): String? =
        eventsDeliveries.poll(timeout, unit)?.let { String(it.body) }
}
