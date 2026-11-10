// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.rabbitmq

import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.failures.FailureRepository
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import com.rabbitmq.client.MessageProperties
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Verifies the broker-side topology declared by [RabbitTopologyBootstrap] with the profile
 * namespaces = ["test-in"] and default prefix "lemline":
 *
 * 1. the per-namespace exchange `lemline-test-in-commands` exists (passive declare);
 * 2. the exchange-to-exchange binding works end to end: a message published to the channel
 *    exchange `lemline-commands` with routing key `test-in` reaches a probe queue bound to
 *    `lemline-test-in-commands`;
 * 3. the pool queue `lemline-commands.test-in` is bound before startup returns: a published
 *    message is delivered to the running consumer — observed
 *    through the failures storage, where the handler persists undeserializable payloads.
 */
@QuarkusTest
@TestProfile(RabbitTopologyBootstrapProfile::class)
@Tag("integration")
@RequiresDocker
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RabbitTopologyBootstrapTest {

    @Inject
    lateinit var failureRepository: FailureRepository

    private lateinit var connection: Connection

    private val namespace = "test-in"
    private val channelExchange = "lemline-commands"
    private val namespaceExchange = "lemline-test-in-commands"
    private val poolQueue = "lemline-commands.test-in"

    @BeforeAll
    fun connect() {
        val config = ConfigProvider.getConfig()
        val factory = ConnectionFactory().apply {
            host = config.getValue("rabbitmq-host", String::class.java)
            port = config.getValue("rabbitmq-port", String::class.java).toInt()
            username = config.getValue("rabbitmq-username", String::class.java)
            password = config.getValue("rabbitmq-password", String::class.java)
        }
        connection = factory.newConnection("lemline-rabbit-topology-test")
    }

    @AfterAll
    fun close() {
        if (::connection.isInitialized) connection.close()
    }

    @Test
    fun `name derivation matches LemlineConfigSource conventions`() {
        assertEquals(
            "lemline-commands",
            RabbitTopologyNames.channelExchange("lemline", DestinationType.COMMANDS)
        )
        assertEquals(
            "lemline-acme-commands",
            RabbitTopologyNames.namespaceExchange("lemline", "acme", DestinationType.COMMANDS)
        )
        // Namespaces are sorted before being dot-joined
        assertEquals(
            "lemline-commands.acme.team",
            RabbitTopologyNames.poolQueue("lemline", DestinationType.COMMANDS, listOf("team", "acme"))
        )
        assertEquals(
            "lemline-cloudevents.acme",
            RabbitTopologyNames.poolQueue("lemline", DestinationType.CLOUDEVENTS, listOf("acme"))
        )
    }

    @Test
    fun `declares namespace exchanges and binds the pool queue`() {
        // 1 — exchanges are declared synchronously at startup
        await("channel exchange '$channelExchange'") { exchangeExists(channelExchange) }
        await("namespace exchange '$namespaceExchange'") { exchangeExists(namespaceExchange) }
        await("namespace exchange 'lemline-test-in-events'") { exchangeExists("lemline-test-in-events") }

        // 2 — channel exchange --test-in--> namespace exchange, observed via a probe queue
        connection.createChannel().use { channel ->
            val probeQueue = channel.queueDeclare().queue // server-named, exclusive, auto-delete
            channel.queueBind(probeQueue, namespaceExchange, "#")

            val probePayload = "probe-${UUID.randomUUID()}"
            channel.basicPublish(
                channelExchange,
                namespace, // routing key = the message's namespace
                MessageProperties.PERSISTENT_TEXT_PLAIN,
                probePayload.toByteArray()
            )
            val received: GetResponse = awaitNotNull("message routed to the probe queue") {
                channel.basicGet(probeQueue, true)
            }
            assertEquals(probePayload, String(received.body))
        }

        // Publish exactly once: retrying publication would hide messages lost before binding.
        await("pool queue '$poolQueue'") { queueExists(poolQueue) }
        val garbage = "garbage-${UUID.randomUUID()}"
        connection.createChannel().use { channel ->
            channel.basicPublish(
                channelExchange,
                namespace,
                MessageProperties.PERSISTENT_TEXT_PLAIN,
                garbage.toByteArray()
            )
            await("garbage payload delivered to the consumer through the pool queue", timeout = 90.seconds) {
                runBlocking { failureRepository.listAll() }.any { it.payload == garbage }
            }
        }
    }

    // ── AMQP helpers ─────────────────────────────────────────────────────────────────────────

    /** Passive declare on a throwaway channel: a failed passive declare closes the channel. */
    private fun exchangeExists(exchange: String): Boolean = passively { it.exchangeDeclarePassive(exchange) }

    private fun queueExists(queue: String): Boolean = passively { it.queueDeclarePassive(queue) }

    private fun passively(check: (com.rabbitmq.client.Channel) -> Unit): Boolean {
        val channel = connection.createChannel()
        return try {
            check(channel)
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { if (channel.isOpen) channel.close() }
        }
    }

    // ── Polling helpers ──────────────────────────────────────────────────────────────────────

    private fun await(
        what: String,
        timeout: Duration = 30.seconds,
        pollDelay: Duration = 250.milliseconds,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(pollDelay.inWholeMilliseconds)
        }
        fail<Unit>("Timed out after $timeout waiting for $what")
    }

    private fun <T : Any> awaitNotNull(
        what: String,
        timeout: Duration = 30.seconds,
        pollDelay: Duration = 250.milliseconds,
        supplier: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(pollDelay.inWholeMilliseconds)
        }
        return fail("Timed out after $timeout waiting for $what")
    }
}
