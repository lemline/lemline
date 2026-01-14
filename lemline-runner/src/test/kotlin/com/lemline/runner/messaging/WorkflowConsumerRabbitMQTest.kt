// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

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
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance


/**
 * Runs the WorkflowConsumerTest suite against a RabbitMQ broker.
 */
@QuarkusTest
@TestProfile(RabbitMQProfile::class)
@Tag("integration")
@RequiresDocker
@ExperimentalTime
@ExperimentalSerializationApi
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WorkflowConsumerRabbitMQTest : WorkflowConsumerTest() {

    private lateinit var instanceQueueIn: String
    private lateinit var instanceQueueOut: String
    private lateinit var databaseQueueIn: String
    private lateinit var databaseQueueOut: String

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

        instanceQueueIn = config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.queue.name", String::class.java)
        instanceQueueOut = config.getValue("mp.messaging.outgoing.$COMMANDS_OUT_CHANNEL.queue.name", String::class.java)
        databaseQueueIn = config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.queue.name", String::class.java)
        databaseQueueOut = config.getValue("mp.messaging.outgoing.$EVENTS_OUT_CHANNEL.queue.name", String::class.java)

        // In testing, queues In and Out must be different
        require(instanceQueueIn != instanceQueueOut) {
            "For RabbitMQ *testing*, queues In ($instanceQueueIn) and Out ($instanceQueueOut) must be different"
        }
        require(databaseQueueIn != databaseQueueOut) {
            "For RabbitMQ *testing*, queues In ($databaseQueueIn) and Out ($databaseQueueOut) must be different"
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

        // Declare the incoming queue with DLQ settings matching LemlineConfigSource
        val commandsDlx =
            config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.dead-letter-exchange", String::class.java)
        val commandsDlq =
            config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.dead-letter-routing-key", String::class.java)
        val commandsArgs = mapOf(
            "x-dead-letter-exchange" to commandsDlx,
            "x-dead-letter-routing-key" to commandsDlq
        )
        commandsChannel.queueDeclare(instanceQueueIn, true, false, false, commandsArgs)

        val eventsDlx =
            config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.dead-letter-exchange", String::class.java)
        val eventsDlq =
            config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.dead-letter-routing-key", String::class.java)
        val eventsArgs = mapOf(
            "x-dead-letter-exchange" to eventsDlx,
            "x-dead-letter-routing-key" to eventsDlq
        )
        eventsChannel.queueDeclare(databaseQueueIn, true, false, false, eventsArgs)

        // Explicitly declare the exchange that SmallRye will default to
        commandsChannel.exchangeDeclare(COMMANDS_OUT_CHANNEL, "topic", true)
        eventsChannel.exchangeDeclare(EVENTS_OUT_CHANNEL, "topic", true)

        // Declare the outgoing queue (where this test consumes)
        commandsChannel.queueDeclare(instanceQueueOut, true, false, false, null)
        eventsChannel.queueDeclare(databaseQueueOut, true, false, false, null)

        // Bind the outgoing queue to the exchange with an EMPTY routing key,
        // matching the default behavior observed in the logs.
        commandsChannel.queueBind(instanceQueueOut, COMMANDS_OUT_CHANNEL, "") // routingKey = ""
        eventsChannel.queueBind(databaseQueueOut, EVENTS_OUT_CHANNEL, "") // routingKey = ""

        // Setup consumer callbacks
        val instanceCallback = DeliverCallback { _, delivery ->
            println("Received message on output queue: ${String(delivery.body)}")
            commandsDeliveries.offer(delivery)
        }
        commandsChannel.basicConsume(instanceQueueOut, true, instanceCallback) { }
        val databaseCallback = DeliverCallback { _, delivery ->
            println("Received message on output queue: ${String(delivery.body)}")
            eventsDeliveries.offer(delivery)
        }
        eventsChannel.basicConsume(databaseQueueOut, true, databaseCallback) { }
    }

    @AfterAll
    fun closeClients() {
        if (::commandsChannel.isInitialized) commandsChannel.close()
        if (::eventsChannel.isInitialized) eventsChannel.close()
        if (::connection.isInitialized) connection.close()
    }

    override fun setupMessaging() {
        // Purge queues to ensure clean state between tests
        commandsChannel.queuePurge(instanceQueueIn)
        commandsChannel.queuePurge(instanceQueueOut)
        eventsChannel.queuePurge(databaseQueueIn)
        eventsChannel.queuePurge(databaseQueueOut)

        // Clear in-memory delivery queues
        commandsDeliveries.clear()
        eventsDeliveries.clear()
    }

    override fun cleanupMessaging() {
        // No-op - clients are closed in @AfterAll
    }

    override fun sendInstanceMessage(message: String) {
        commandsChannel.basicPublish(
            "",
            instanceQueueIn,
            MessageProperties.PERSISTENT_TEXT_PLAIN,
            message.toByteArray()
        )
    }

    override suspend fun receiveInstanceMessage(timeout: Long, unit: TimeUnit): String? =
        commandsDeliveries.poll(timeout, unit)?.let { String(it.body) }

    override suspend fun receivedEvent(timeout: Long, unit: TimeUnit): String? =
        eventsDeliveries.poll(timeout, unit)?.let { String(it.body) }
}
