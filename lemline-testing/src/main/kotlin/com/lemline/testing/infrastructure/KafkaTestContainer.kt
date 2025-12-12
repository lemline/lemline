// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing.infrastructure

import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Kafka container wrapper for E2E testing.
 *
 * Provides a pre-configured Kafka instance using Testcontainers.
 */
class KafkaTestContainer : AutoCloseable {

    private val container = KafkaContainer(
        DockerImageName.parse("apache/kafka:3.7.0")
    )

    /**
     * Start the Kafka container.
     */
    fun start() {
        container.start()
    }

    /**
     * Stop the Kafka container.
     */
    override fun close() {
        container.stop()
    }

    /**
     * Get the bootstrap servers connection string.
     */
    fun getBootstrapServers(): String = container.bootstrapServers

    /**
     * Check if the container is running.
     */
    fun isRunning(): Boolean = container.isRunning
}
