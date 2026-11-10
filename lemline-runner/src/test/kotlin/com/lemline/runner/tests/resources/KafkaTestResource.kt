// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.kafka.KafkaContainer

class KafkaTestResource : TestcontainersResource() {
    private lateinit var kafka: KafkaContainer

    override fun startContainers(): Map<String, String> {
        // Create Kafka container using Apache Kafka native image (KRaft mode)
        kafka = KafkaContainer("apache/kafka:3.7.0")

        // Start Kafka
        kafka.start()

        val servers = kafka.bootstrapServers

        // Pre-create topics that Quarkus consumers will subscribe to
        // This prevents UNKNOWN_TOPIC_OR_PARTITION errors during startup
        createTopics(servers)

        // Return the bootstrap servers configuration using lemline.* property
        // LemlineConfigSource transforms lemline.messaging.kafka.brokers to kafka.bootstrap.servers
        val properties = mapOf(com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_BROKERS to servers)

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
                // Per-namespace destinations (<prefix>-<namespace>-<type>) used by the suites:
                // "test-in" is consumed by the runner, "test"/"default" carry observed emissions
                NewTopic("lemline-test-in-commands", 1, 1.toShort()),
                NewTopic("lemline-test-in-events", 1, 1.toShort()),
                NewTopic("lemline-test-commands", 1, 1.toShort()),
                NewTopic("lemline-test-events", 1, 1.toShort()),
                NewTopic("lemline-default-commands", 1, 1.toShort()),
                NewTopic("lemline-default-events", 1, 1.toShort()),
                // DLQ topics of the consumed destinations
                NewTopic("lemline-test-in-commands.dlq", 1, 1.toShort()),
                NewTopic("lemline-test-in-events.dlq", 1, 1.toShort()),
            )
            admin.createTopics(topics).all().get()
        }
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty(com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_BROKERS)

        if (::kafka.isInitialized) {
            kafka.stop()
            kafka.close()
        }
    }
}
