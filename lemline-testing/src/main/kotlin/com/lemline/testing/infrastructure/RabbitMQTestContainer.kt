// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing.infrastructure

import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

/**
 * RabbitMQ container wrapper for E2E testing.
 *
 * Provides a pre-configured RabbitMQ instance using Testcontainers.
 */
class RabbitMQTestContainer : AutoCloseable {

    private val container = RabbitMQContainer(
        DockerImageName.parse("rabbitmq:3.13-management")
    )

    /**
     * Start the RabbitMQ container.
     */
    fun start() {
        container.start()
    }

    /**
     * Stop the RabbitMQ container.
     */
    override fun close() {
        container.stop()
    }

    /**
     * Get the AMQP connection URL.
     */
    fun getAmqpUrl(): String = container.amqpUrl

    /**
     * Get the host.
     */
    fun getHost(): String = container.host

    /**
     * Get the AMQP port.
     */
    fun getAmqpPort(): Int = container.amqpPort

    /**
     * Get the username.
     */
    fun getUsername(): String = container.adminUsername

    /**
     * Get the password.
     */
    fun getPassword(): String = container.adminPassword

    /**
     * Check if the container is running.
     */
    fun isRunning(): Boolean = container.isRunning
}
