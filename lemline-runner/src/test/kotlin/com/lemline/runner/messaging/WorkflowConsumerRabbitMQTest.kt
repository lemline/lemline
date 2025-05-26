// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.EnabledOnlyIfDockerAvailable
import com.lemline.runner.messaging.bases.WorkflowConsumerTest
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
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag


/**
 * Runs the WorkflowConsumerTest suite against a RabbitMQ broker.
 */
@QuarkusTest
@TestProfile(RabbitMQProfile::class)
@Tag("integration")
@EnabledOnlyIfDockerAvailable
internal class WorkflowConsumerRabbitMQTest : WorkflowConsumerTest() {

    @ConfigProperty(name = "rabbitmq-host")
    lateinit var rabbitmqHost: String

    @ConfigProperty(name = "rabbitmq-port")
    lateinit var rabbitmqPort: String

    @ConfigProperty(name = "rabbitmq-username")
    lateinit var rabbitmqUsername: String

    @ConfigProperty(name = "rabbitmq-password")
    lateinit var rabbitmqPassword: String

    @ConfigProperty(name = "mp.messaging.incoming.$WORKFLOW_IN.queue.name")
    lateinit var queueIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.$WORKFLOW_OUT.queue.name")
    lateinit var queueOut: String

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private val deliveries = LinkedBlockingQueue<Delivery>()

    override fun setupMessaging() {
        // Setup RabbitMQ connection
        val factory = ConnectionFactory().apply {
            host = rabbitmqHost
            port = rabbitmqPort.toInt()
            username = rabbitmqUsername
            password = rabbitmqPassword
        }
        connection = factory.newConnection()
        channel = connection.createChannel()

        // In testing, queues In and Out must be different
        require(queueIn != queueOut) {
            "For RabbitMQ *testing*, queues In ($queueIn) and Out ($queueOut) must be different"
        }

        // Declare the incoming queue
        channel.queueDeclare(queueIn, true, false, false, null)

        // Explicitly declare the exchange that SmallRye will default to
        channel.exchangeDeclare(WORKFLOW_OUT, "topic", true)

        // Declare the outgoing queue (where this test consumes)
        channel.queueDeclare(queueOut, true, false, false, null)

        // Bind the outgoing queue to the exchange with an EMPTY routing key,
        // matching the default behavior observed in the logs.
        channel.queueBind(queueOut, WORKFLOW_OUT, "") // routingKey = ""

        // Purge queues
        channel.queuePurge(queueIn)
        channel.queuePurge(queueOut)

        // Setup consumer
        val deliverCallback = DeliverCallback { _, delivery ->
            println("Received message on output queue: ${String(delivery.body)}")
            deliveries.offer(delivery)
        }
        channel.basicConsume(queueOut, true, deliverCallback) { }
    }

    override fun cleanupMessaging() {
        if (::channel.isInitialized) channel.close()
        if (::connection.isInitialized) connection.close()
    }

    override fun sendMessage(message: String) {
        channel.basicPublish("", queueIn, MessageProperties.PERSISTENT_TEXT_PLAIN, message.toByteArray())
    }

    override fun receiveMessage(timeout: Long, unit: TimeUnit): String? =
        deliveries.poll(timeout, unit)?.let { String(it.body) }
}
