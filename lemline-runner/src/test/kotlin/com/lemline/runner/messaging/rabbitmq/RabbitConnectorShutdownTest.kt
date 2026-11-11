// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.rabbitmq

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.testcases.rabbitmq.RabbitMQTestCaseProfile
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQConnector
import jakarta.enterprise.event.Event
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// Isolate the application because this test fires its shutdown event.
class RabbitConnectorShutdownProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = RabbitMQTestCaseProfile().configOverrides

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        RabbitMQTestCaseProfile().testResources()
}

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitConnectorShutdownProfile::class)
class RabbitConnectorShutdownTest {
    @Inject
    lateinit var config: LemlineConfiguration

    @Inject
    lateinit var shutdown: Event<ShutdownEvent>

    @Inject
    @Any
    lateinit var connector: RabbitMQConnector

    @Test
    fun `application shutdown closes broker connections before CDI destruction`() {
        val port = config.messaging().rabbitmq().orElseThrow().port()
        fun connectionThreads() = Thread.getAllStackTraces().keys.filter {
            it.name.startsWith("AMQP Connection ") && it.name.endsWith(":$port")
        }
        val connections = connectionThreads()
        assertTrue(connections.isNotEmpty(), "Expected active RabbitMQ connections before shutdown")

        try {
            // CDI and Vert.x remain available here. The ShutdownEvent must close native
            // connections now, rather than deferring them to CDI context destruction.
            shutdown.fire(ShutdownEvent())
            connections.forEach { it.join(5_000) }
            val survivors = connectionThreads().filter { it.isAlive }.map { it.name }
            assertTrue(survivors.isEmpty(), "RabbitMQ connections survived shutdown: $survivors")
        } finally {
            connector.terminate(null)
        }
    }
}
