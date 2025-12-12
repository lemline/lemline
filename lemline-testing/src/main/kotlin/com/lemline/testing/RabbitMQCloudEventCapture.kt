// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.common.logger.logger
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import io.cloudevents.CloudEvent
import io.cloudevents.core.format.EventFormat
import io.cloudevents.core.provider.EventFormatProvider
import io.cloudevents.jackson.JsonFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RabbitMQ-based CloudEvent capture implementation.
 *
 * Subscribes to RabbitMQ queue and captures CloudEvents for registered workflows.
 */
class RabbitMQCloudEventCapture(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val queueName: String,
    private val dispatcher: CloudEventDispatcher = CloudEventDispatcher.getInstance()
) : AutoCloseable {

    private val logger = logger()
    private var connection: Connection? = null
    private var channel: Channel? = null
    private var consumerTag: String? = null

    private val eventFormat: EventFormat = EventFormatProvider.getInstance()
        .resolveFormat(JsonFormat.CONTENT_TYPE)
        ?: throw IllegalStateException("JSON CloudEvent format not available")

    /**
     * Start consuming events from RabbitMQ.
     */
    suspend fun start() {
        if (connection != null) {
            logger.warn { "RabbitMQ capture already started" }
            return
        }

        withContext(Dispatchers.IO) {
            val factory = ConnectionFactory().apply {
                this.host = this@RabbitMQCloudEventCapture.host
                this.port = this@RabbitMQCloudEventCapture.port
                this.username = this@RabbitMQCloudEventCapture.username
                this.password = this@RabbitMQCloudEventCapture.password
            }

            connection = factory.newConnection()
            channel = connection?.createChannel()

            // Declare queue if it doesn't exist
            channel?.queueDeclare(queueName, true, false, false, null)

            val deliverCallback = DeliverCallback { _, delivery ->
                try {
                    val event = eventFormat.deserialize(delivery.body)
                    dispatcher.dispatch(event)
                } catch (e: Exception) {
                    logger.error(e) { "Error deserializing CloudEvent from RabbitMQ" }
                }
            }

            consumerTag = channel?.basicConsume(queueName, true, deliverCallback) { _ -> }

            logger.info { "Started RabbitMQ capture on queue: $queueName" }
        }
    }

    /**
     * Stop consuming events.
     */
    suspend fun stop() {
        withContext(Dispatchers.IO) {
            consumerTag?.let { tag ->
                try {
                    channel?.basicCancel(tag)
                } catch (e: Exception) {
                    logger.debug { "Error canceling consumer: ${e.message}" }
                }
            }
            consumerTag = null

            channel?.close()
            channel = null

            connection?.close()
            connection = null

            logger.info { "Stopped RabbitMQ capture" }
        }
    }

    override fun close() {
        channel?.close()
        connection?.close()
    }
}
