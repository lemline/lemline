package com.lemline.runtime.messaging

import com.lemline.runtime.RabbitMQTestProfile
import com.lemline.runtime.RabbitMQTestResource
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

    override val inputTopic: String
        get() = queueIn

    override val outputTopic: String
        get() = queueOut

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

        // Declare queue
        require(queueIn == queueOut) { "Queues In and Out must be equal: $queueIn != $queueOut" }
        channel.queueDeclare(queueIn, true, false, false, null)

        // Setup consumer
        val deliverCallback = DeliverCallback { _, delivery -> deliveries.offer(delivery) }
        channel.basicConsume(queueOut, true, deliverCallback, CancelCallback { })
    }

    override fun cleanupMessaging() {
        channel.close()
        connection.close()
    }

    override fun sendMessage(message: String) {
        channel.basicPublish("", queueIn, MessageProperties.PERSISTENT_TEXT_PLAIN, message.toByteArray())
    }

    override fun receiveMessage(timeout: Long, unit: TimeUnit): String? =
        deliveries.poll(timeout, unit)?.let { String(it.body) }
} 