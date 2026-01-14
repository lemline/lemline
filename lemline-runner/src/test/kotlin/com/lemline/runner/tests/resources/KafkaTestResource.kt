// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
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

        // Pre-create topics that Quarkus consumers will subscribe to
        // This prevents UNKNOWN_TOPIC_OR_PARTITION errors during startup
        createTopics(servers)

        // Return only the bootstrap servers configuration
        val properties = mapOf("kafka.bootstrap.servers" to servers)

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    /**
     * Pre-creates Kafka topics before Quarkus starts.
     * This ensures consumers can subscribe without UNKNOWN_TOPIC_OR_PARTITION errors.
     */
    private fun createTopics(bootstrapServers: String) {
        val adminProps = mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)
        AdminClient.create(adminProps).use { admin ->
            val topics = listOf(
                // Topics for WorkflowConsumerKafkaTest (separate in/out for testing)
                NewTopic("lemline-commands-in", 1, 1.toShort()),
                NewTopic("lemline-commands-out", 1, 1.toShort()),
                NewTopic("lemline-events-in", 1, 1.toShort()),
                NewTopic("lemline-events-out", 1, 1.toShort()),
                // DLQ topics
                NewTopic("lemline-commands-in.dlq", 1, 1.toShort()),
                NewTopic("lemline-events-in.dlq", 1, 1.toShort()),
            )
            admin.createTopics(topics).all().get()
        }
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
