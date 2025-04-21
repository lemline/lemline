// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.messaging

import com.lemline.worker.messaging.bases.WorkflowConsumerBaseTest
import com.lemline.worker.tests.profiles.RabbitMQTestProfile
import com.lemline.worker.tests.resources.RabbitMQTestResource
import com.rabbitmq.client.*
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Tag
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(RabbitMQTestResource::class)
@TestProfile(RabbitMQTestProfile::class)
@Tag("integration")
internal class WorkflowConsumerRabbitMQTest : WorkflowConsumerBaseTest() {

    @ConfigProperty(name = "rabbitmq-host")
    lateinit var rabbitmqHost: String

    @ConfigProperty(name = "rabbitmq-port")
    lateinit var rabbitmqPort: String

    @ConfigProperty(name = "rabbitmq-username")
    lateinit var rabbitmqUsername: String

    @ConfigProperty(name = "rabbitmq-password")
    lateinit var rabbitmqPassword: String

    @ConfigProperty(name = "mp.messaging.incoming.workflows-in.queue.name")
    lateinit var queueIn: String

    @ConfigProperty(name = "mp.messaging.outgoing.workflows-out.queue.name")
    lateinit var queueOut: String

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private val deliveries: BlockingQueue<Delivery> = LinkedBlockingQueue()

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

        // Declare queues and exchange, then bind for test consumer
        require(queueIn != queueOut) {
            "For RabbitMQ *testing*, queues In ($queueIn) and Out ($queueOut) must be different"
        }

        // Declare the incoming queue
        channel.queueDeclare(queueIn, true, false, false, null)

        // Explicitly declare the exchange that SmallRye will default to
        channel.exchangeDeclare(queueOut, "topic", true) // Exchange name = queueOut, type=topic, durable=true

        // Declare the outgoing queue (where this test consumes)
        channel.queueDeclare(queueOut, true, false, false, null)

        // Bind the outgoing queue to the exchange with an EMPTY routing key,
        // matching the default behavior observed in the logs.
        channel.queueBind(queueOut, queueOut, "") // routingKey = ""

        // Purge queues
        channel.queuePurge(queueIn)
        channel.queuePurge(queueOut)

        // Setup consumer
        val deliverCallback = DeliverCallback { _, delivery ->
            println("Received message on output queue: ${String(delivery.body)}")
            deliveries.offer(delivery)
        }
        channel.basicConsume(queueOut, true, deliverCallback, CancelCallback { })
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
