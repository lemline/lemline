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

        // Get the bootstrap servers
        val bootstrapServers = kafka.bootstrapServers ?: throw RuntimeException("Failed to start Kafka container")

        System.setProperty("kafka.bootstrap.servers", bootstrapServers)
        
        return mapOf(
            "kafka.bootstrap.servers" to bootstrapServers,
            "mp.messaging.incoming.workflows-in.connector" to "smallrye-kafka",
            "mp.messaging.incoming.workflows-in.topic" to "workflows-in",
            "mp.messaging.incoming.workflows-in.bootstrap.servers" to bootstrapServers,
            "mp.messaging.incoming.workflows-in.group.id" to "test-group",
            "mp.messaging.incoming.workflows-in.auto.offset.reset" to "earliest",
            "mp.messaging.outgoing.workflows-out.connector" to "smallrye-kafka",
            "mp.messaging.outgoing.workflows-out.topic" to "workflows-out",
            "mp.messaging.outgoing.workflows-out.bootstrap.servers" to bootstrapServers,
            "mp.messaging.outgoing.workflows-out.value.serializer" to "org.apache.kafka.common.serialization.StringSerializer"
        )
    }

    override fun stop() {
        kafka.stop()
        kafka.close()
        network.close()
    }
} 