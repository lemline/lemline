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

        // RabbitMQ with concurrent consumers can process CloudEvents out of order.
        // Increase delay between events to ensure each event completes processing
        System.setProperty("test.workflow.cloudevent-send-interval-ms", "50")

        // Return the RabbitMQ connection configuration
        // Set both lemline.* properties (for LemlineConfiguration/MessagingStartupValidator)
        // and rabbitmq-* properties (for SmallRye RabbitMQ connector)
        val properties = mapOf(
            // Lemline config properties (read by MessagingStartupValidator via LemlineConfiguration)
            "lemline.messaging.rabbitmq.hostname" to rabbitmq.host,
            "lemline.messaging.rabbitmq.port" to rabbitmq.getMappedPort(5672).toString(),
            "lemline.messaging.rabbitmq.username" to rabbitmq.adminUsername,
            "lemline.messaging.rabbitmq.password" to rabbitmq.adminPassword,
            // SmallRye connector properties (used by reactive messaging)
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
        System.clearProperty("lemline.messaging.rabbitmq.hostname")
        System.clearProperty("lemline.messaging.rabbitmq.port")
        System.clearProperty("lemline.messaging.rabbitmq.username")
        System.clearProperty("lemline.messaging.rabbitmq.password")
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
