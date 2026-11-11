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
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.microprofile.config.inject.ConfigProperty
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

    @ConfigProperty(name = "test.rabbitmq.management-url")
    lateinit var managementUrl: String

    @Test
    fun `application shutdown closes broker connections before CDI destruction`() = runBlocking {
        try {
            // Connector startup and RabbitMQ management statistics are asynchronous.
            // Observe all four incoming connections before exercising shutdown so an
            // initially empty management response cannot make the test pass spuriously.
            val incomingChannels = setOf("commands-in", "events-in", "cloudevents-in", "lifecycleevents-in")
            awaitConnections("Expected all incoming RabbitMQ connections before shutdown") { connections ->
                incomingChannels.all { channel -> connections.any { it.contains("$channel (Incoming)") } }
            }
            // CDI and Vert.x remain available here. The ShutdownEvent must close native
            // connections now, rather than deferring them to CDI context destruction.
            shutdown.fire(ShutdownEvent())
            awaitConnections("RabbitMQ connections survived shutdown") { it.isEmpty() }
        } finally {
            connector.terminate(null)
        }
    }

    private suspend fun awaitConnections(message: String, condition: (List<String>) -> Boolean) {
        var observed = emptyList<String>()
        val reached = withTimeoutOrNull(30_000) {
            while (true) {
                observed = connections()
                if (condition(observed)) return@withTimeoutOrNull true
                delay(100)
            }
        }
        assertEquals(true, reached, "$message: $observed")
    }

    private fun connections(): List<String> {
        val rabbit = config.messaging().rabbitmq().orElseThrow()
        val credentials = "${rabbit.username().orElseThrow()}:${rabbit.password().orElseThrow()}"
        val connection = URI.create("$managementUrl/api/connections").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray()))
        return try {
            assertEquals(200, connection.responseCode, "RabbitMQ management API must be available")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Json.parseToJsonElement(body).jsonArray.map {
                val entry = it.jsonObject
                val name = entry.getValue("name").jsonPrimitive.content
                val client = entry["client_properties"]?.jsonObject?.get("connection_name")?.jsonPrimitive?.content
                "$name ($client)"
            }
        } finally {
            connection.disconnect()
        }
    }
}
