// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class RabbitMQTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var rabbitmq: RabbitMQContainer
    private lateinit var network: Network

    override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) {
            return emptyMap()
        }

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
        val properties = mapOf(
            "rabbitmq-host" to rabbitmq.host,
            "rabbitmq-port" to rabbitmq.getMappedPort(5672).toString(),
            "rabbitmq-username" to rabbitmq.adminUsername,
            "rabbitmq-password" to rabbitmq.adminPassword,
        )

        // Set as system properties so that [LemlineConfigSource] can see them.
        properties.forEach { (k, v) -> System.setProperty(k, v) }

        return properties
    }

    override fun stop() {
        // Clear system properties to prevent conflicts with other test profiles
        System.clearProperty("rabbitmq-host")
        System.clearProperty("rabbitmq-port")
        System.clearProperty("rabbitmq-username")
        System.clearProperty("rabbitmq-password")

        if (::rabbitmq.isInitialized) {
            rabbitmq.stop()
            rabbitmq.close()
        }
        if (::network.isInitialized) {
            network.close()
        }
    }
}
