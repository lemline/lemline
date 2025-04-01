package com.lemline.runtime

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class KafkaTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var kafka: KafkaContainer
    private lateinit var network: Network

    override fun start(): Map<String, String> {
        // Create a network for Kafka
        network = Network.newNetwork()

        // Create and configure Kafka container
        kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.3.0"))
            .withNetwork(network)
            .withNetworkAliases("kafka")
            .withKraft()
            .withCreateContainerCmdModifier { cmd -> cmd.withHostName("kafka") }
            .waitingFor(Wait.forListeningPort())

        // Start Kafka
        kafka.start()

        // Return only the bootstrap servers configuration
        return mapOf(
            "kafka.bootstrap.servers" to (kafka.bootstrapServers 
                ?: throw RuntimeException("Failed to start Kafka container"))
        )
    }

    override fun stop() {
        kafka.stop()
        kafka.close()
        network.close()
    }
} 