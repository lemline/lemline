// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class KafkaTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var kafka: KafkaContainer
    private lateinit var network: Network

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

        // Create a network for Kafka
        network = Network.newNetwork()

        // Create and configure a Kafka container
        kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.3.0"))
            .withNetwork(network)
            .withNetworkAliases("kafka")
            .withKraft()
            .withCreateContainerCmdModifier { cmd -> cmd.withHostName("kafka") }
            .waitingFor(Wait.forLogMessage(".*\\[KafkaRaftServer nodeId=.*\\] Kafka Server started.*", 1))

        // Start Kafka
        kafka.start()

        val servers = kafka.bootstrapServers ?: error("Failed to start Kafka container")

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
        if (::network.isInitialized) {
            network.close()
        }
    }
}
