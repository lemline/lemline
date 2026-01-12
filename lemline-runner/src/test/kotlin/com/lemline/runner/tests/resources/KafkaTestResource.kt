// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.kafka.KafkaContainer

class KafkaTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var kafka: KafkaContainer

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

        // Create Kafka container using Apache Kafka native image (KRaft mode)
        kafka = KafkaContainer("apache/kafka:3.7.0")

        // Start Kafka
        kafka.start()

        val servers = kafka.bootstrapServers

        // Return only the bootstrap servers configuration
        val properties = mapOf("kafka.bootstrap.servers" to servers)

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty("kafka.bootstrap.servers")

        if (::kafka.isInitialized) {
            kafka.stop()
            kafka.close()
        }
    }
}
