package com.lemline.worker.tests.resources

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class RabbitMQTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var rabbitmq: RabbitMQContainer
    private lateinit var network: Network

    override fun start(): Map<String, String> {
        // Create a network for RabbitMQ
        network = Network.newNetwork()

        // Create and configure RabbitMQ container
        rabbitmq = RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-management"))
            .withNetwork(network)
            .withNetworkAliases("rabbitmq")
            .withCreateContainerCmdModifier { cmd -> cmd.withHostName("rabbitmq") }
            .withExposedPorts(5672, 15672) // Expose AMQP and management ports
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))

        // Start RabbitMQ
        rabbitmq.start()

        // Return the RabbitMQ connection configuration
        return mapOf(
            "rabbitmq-host" to rabbitmq.host,
            "rabbitmq-port" to rabbitmq.getMappedPort(5672).toString(),
            "rabbitmq-username" to rabbitmq.adminUsername,
            "rabbitmq-password" to rabbitmq.adminPassword
        )
    }

    override fun stop() {
        rabbitmq.stop()
        rabbitmq.close()
        network.close()
    }
} 