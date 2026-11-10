// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.rabbitmq

import com.lemline.runner.config.LemlineConfiguration
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import java.io.IOException
import java.util.Optional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class RabbitTopologyStartupTest {
    private val config = mockk<LemlineConfiguration>(relaxed = true)
    private val commands = mockk<LemlineConfiguration.ChannelConfig>(relaxed = true)
    private val connection = mockk<Connection>(relaxed = true)
    private val channel = mockk<Channel>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { config.messaging().type() } returns "rabbitmq"
        every { config.messaging().prefix() } returns "lemline"
        every { config.namespaces() } returns Optional.of(listOf("test"))
        every { config.messaging().rabbitmq() } returns Optional.empty()
        every { config.messaging().events() } returns Optional.empty()
        every { config.messaging().cloudevents() } returns Optional.empty()
        every { commands.producer().enabled() } returns true
        every { commands.consumer().enabled() } returns true
        every { config.messaging().commands() } returns Optional.of(commands)
        mockkConstructor(ConnectionFactory::class)
        every { anyConstructed<ConnectionFactory>().newConnection(any<String>()) } returns connection
        every { connection.createChannel() } returns channel
    }

    @AfterEach
    fun cleanup() {
        unmockkConstructor(ConnectionFactory::class)
    }

    @Test
    fun `startup waits for the connector queue and binds it before returning`() {
        // The connector has not declared its queue at the first probe.
        every { channel.queueDeclarePassive("lemline-commands.test") } throws IOException("NOT_FOUND") andThen mockk<com.rabbitmq.client.AMQP.Queue.DeclareOk>()

        RabbitTopologyBootstrap(config).onStart(StartupEvent())

        verify(exactly = 2) { channel.queueDeclarePassive("lemline-commands.test") }
        verify(exactly = 1) { channel.queueBind("lemline-commands.test", "lemline-test-commands", "#") }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `binding failure fails startup and closes the connection`() {
        every { channel.queueBind(any(), any(), any()) } throws IOException("ACCESS_REFUSED")

        assertThrows(IOException::class.java) { RabbitTopologyBootstrap(config).onStart(StartupEvent()) }

        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `producer only startup does not wait for a consumer queue`() {
        every { commands.consumer().enabled() } returns false

        RabbitTopologyBootstrap(config).onStart(StartupEvent())

        verify(exactly = 0) { channel.queueDeclarePassive(any()) }
        verify(exactly = 1) { connection.close() }
    }
}
