// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.WORKFLOWS_IN_CHANNEL
import com.lemline.runner.messaging.commands.WORKFLOWS_OUT_CHANNEL
import com.lemline.runner.messaging.events.DATABASE_IN_CHANNEL
import com.lemline.runner.messaging.events.DATABASE_OUT_CHANNEL
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag


/**
 * Runs the WorkflowConsumerTest suite against a RabbitMQ broker.
 */
@QuarkusTest
@TestProfile(RabbitMQProfile::class)
@Tag("integration")
@EnabledOnlyIfDockerAvailable
@ExperimentalTime
@ExperimentalSerializationApi
internal class WorkflowConsumerRabbitMQTest : WorkflowConsumerTest() {

    @ConfigProperty(name = "rabbitmq-host")
    lateinit var rabbitmqHost: String

    @ConfigProperty(name = "rabbitmq-port")
    lateinit var rabbitmqPort: String

    @ConfigProperty(name = "rabbitmq-username")
    lateinit var rabbitmqUsername: String

    @ConfigProperty(name = "rabbitmq-password")
    lateinit var rabbitmqPassword: String

    @ConfigProperty(name = "mp.messaging.incoming.$WORKFLOWS_IN_CHANNEL.queue.name")
    lateinit var instanceQueueIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.$WORKFLOWS_OUT_CHANNEL.queue.name")
    lateinit var instanceQueueOut: String

    @ConfigProperty(name = "mp.messaging.incoming.$DATABASE_IN_CHANNEL.queue.name")
    lateinit var databaseQueueIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.$DATABASE_OUT_CHANNEL.queue.name")
    lateinit var databaseQueueOut: String

    private lateinit var connection: Connection
    private lateinit var instanceChannel: Channel
    private lateinit var databaseChannel: Channel
    private val instanceDeliveries = LinkedBlockingQueue<Delivery>()
    private val databaseDeliveries = LinkedBlockingQueue<Delivery>()

    override fun setupMessaging() {
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

        // In testing, queues In and Out must be different
        require(instanceQueueIn != instanceQueueOut) {
            "For RabbitMQ *testing*, queues In ($instanceQueueIn) and Out ($instanceQueueOut) must be different"
        }
        require(databaseQueueIn != databaseQueueOut) {
            "For RabbitMQ *testing*, queues In ($databaseQueueIn) and Out ($databaseQueueOut) must be different"
        }

        // Declare the incoming queue
        val instanceArgs = mapOf(
            "x-dead-letter-exchange" to "$WORKFLOWS_IN_CHANNEL.dlx",
            "x-dead-letter-routing-key" to "lemline-workflows.dlq"
        )
        instanceChannel.queueDeclare(instanceQueueIn, true, false, false, instanceArgs)
        val databaseArgs = mapOf(
            "x-dead-letter-exchange" to "$DATABASE_IN_CHANNEL.dlx",
            "x-dead-letter-routing-key" to "lemline-ingestion.dlq"
        )
        databaseChannel.queueDeclare(databaseQueueIn, true, false, false, databaseArgs)

        // Explicitly declare the exchange that SmallRye will default to
        instanceChannel.exchangeDeclare(WORKFLOWS_OUT_CHANNEL, "topic", true)
        databaseChannel.exchangeDeclare(DATABASE_OUT_CHANNEL, "topic", true)

        // Declare the outgoing queue (where this test consumes)
        instanceChannel.queueDeclare(instanceQueueOut, true, false, false, null)
        databaseChannel.queueDeclare(databaseQueueOut, true, false, false, null)

        // Bind the outgoing queue to the exchange with an EMPTY routing key,
        // matching the default behavior observed in the logs.
        instanceChannel.queueBind(instanceQueueOut, WORKFLOWS_OUT_CHANNEL, "") // routingKey = ""
        databaseChannel.queueBind(databaseQueueOut, DATABASE_OUT_CHANNEL, "") // routingKey = ""

        // Purge queues
        instanceChannel.queuePurge(instanceQueueIn)
        instanceChannel.queuePurge(instanceQueueOut)
        databaseChannel.queuePurge(databaseQueueIn)
        databaseChannel.queuePurge(databaseQueueOut)

        // Setup consumer
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

    override fun cleanupMessaging() {
        if (::instanceChannel.isInitialized) instanceChannel.close()
        if (::databaseChannel.isInitialized) databaseChannel.close()
        if (::connection.isInitialized) connection.close()
    }

    override fun sendInstanceMessage(message: String) {
        instanceChannel.basicPublish(
            "",
            instanceQueueIn,
            MessageProperties.PERSISTENT_TEXT_PLAIN,
            message.toByteArray()
        )
    }

    override fun receiveInstanceMessage(timeout: Long, unit: TimeUnit): String? =
        instanceDeliveries.poll(timeout, unit)?.let { String(it.body) }

    override fun receiveDatabaseMessage(timeout: Long, unit: TimeUnit): String? =
        databaseDeliveries.poll(timeout, unit)?.let { String(it.body) }
}
