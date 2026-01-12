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
    private lateinit var instanceChannel: Channel
    private lateinit var databaseChannel: Channel
    private val instanceDeliveries = LinkedBlockingQueue<Delivery>()
    private val databaseDeliveries = LinkedBlockingQueue<Delivery>()

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
        instanceChannel = connection.createChannel()
        databaseChannel = connection.createChannel()

        // Declare the incoming queue
        val instanceArgs = mapOf(
            "x-dead-letter-exchange" to "$COMMANDS_IN_CHANNEL.dlx",
            "x-dead-letter-routing-key" to "lemline-commands.dlq"
        )
        instanceChannel.queueDeclare(instanceQueueIn, true, false, false, instanceArgs)
        val databaseArgs = mapOf(
            "x-dead-letter-exchange" to "$EVENTS_IN_CHANNEL.dlx",
            "x-dead-letter-routing-key" to "lemline-events.dlq"
        )
        databaseChannel.queueDeclare(databaseQueueIn, true, false, false, databaseArgs)

        // Explicitly declare the exchange that SmallRye will default to
        instanceChannel.exchangeDeclare(COMMANDS_OUT_CHANNEL, "topic", true)
        databaseChannel.exchangeDeclare(EVENTS_OUT_CHANNEL, "topic", true)

        // Declare the outgoing queue (where this test consumes)
        instanceChannel.queueDeclare(instanceQueueOut, true, false, false, null)
        databaseChannel.queueDeclare(databaseQueueOut, true, false, false, null)

        // Bind the outgoing queue to the exchange with an EMPTY routing key,
        // matching the default behavior observed in the logs.
        instanceChannel.queueBind(instanceQueueOut, COMMANDS_OUT_CHANNEL, "") // routingKey = ""
        databaseChannel.queueBind(databaseQueueOut, EVENTS_OUT_CHANNEL, "") // routingKey = ""

        // Setup consumer callbacks
        val instanceCallback = DeliverCallback { _, delivery ->
            println("Received message on output queue: ${String(delivery.body)}")
            instanceDeliveries.offer(delivery)
        }
        instanceChannel.basicConsume(instanceQueueOut, true, instanceCallback) { }
        val databaseCallback = DeliverCallback { _, delivery ->
            println("Received message on output queue: ${String(delivery.body)}")
            databaseDeliveries.offer(delivery)
        }
        databaseChannel.basicConsume(databaseQueueOut, true, databaseCallback) { }
    }

    @AfterAll
    fun closeClients() {
        if (::instanceChannel.isInitialized) instanceChannel.close()
        if (::databaseChannel.isInitialized) databaseChannel.close()
        if (::connection.isInitialized) connection.close()
    }

    override fun setupMessaging() {
        // Purge queues to ensure clean state between tests
        instanceChannel.queuePurge(instanceQueueIn)
        instanceChannel.queuePurge(instanceQueueOut)
        databaseChannel.queuePurge(databaseQueueIn)
        databaseChannel.queuePurge(databaseQueueOut)

        // Clear in-memory delivery queues
        instanceDeliveries.clear()
        databaseDeliveries.clear()
    }

    override fun cleanupMessaging() {
        // No-op - clients are closed in @AfterAll
    }

    override fun sendInstanceMessage(message: String) {
        instanceChannel.basicPublish(
            "",
            instanceQueueIn,
            MessageProperties.PERSISTENT_TEXT_PLAIN,
            message.toByteArray()
        )
    }

    override suspend fun receiveInstanceMessage(timeout: Long, unit: TimeUnit): String? =
        instanceDeliveries.poll(timeout, unit)?.let { String(it.body) }

    override suspend fun receivedEvent(timeout: Long, unit: TimeUnit): String? =
        databaseDeliveries.poll(timeout, unit)?.let { String(it.body) }
}
