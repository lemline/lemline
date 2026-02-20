// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.messaging.pgmq.PgmqClient
import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import com.lemline.runner.tests.profiles.PgmqProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WorkflowConsumerTest suite against a PGMQ (PostgreSQL Message Queue) broker.
 */
@QuarkusTest
@TestProfile(PgmqProfile::class)
@Tag("integration")
@RequiresDocker
@ExperimentalTime
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WorkflowConsumerPgmqTest : WorkflowConsumerTest() {

    private lateinit var commandsInClient: PgmqClient
    private lateinit var commandsOutClient: PgmqClient
    private lateinit var eventsInClient: PgmqClient
    private lateinit var eventsOutClient: PgmqClient

    @BeforeAll
    fun initClients() {
        val config = ConfigProvider.getConfig()

        val commandsQueueIn = config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.queue", String::class.java)
        val commandsQueueOut = config.getValue("mp.messaging.outgoing.$COMMANDS_OUT_CHANNEL.queue", String::class.java)
        val eventsQueueIn = config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.queue", String::class.java)
        val eventsQueueOut = config.getValue("mp.messaging.outgoing.$EVENTS_OUT_CHANNEL.queue", String::class.java)

        require(commandsQueueIn != commandsQueueOut) {
            "For *testing*, queues In ($commandsQueueIn) and Out ($commandsQueueOut) must be different"
        }
        require(eventsQueueIn != eventsQueueOut) {
            "For *testing*, queues In ($eventsQueueIn) and Out ($eventsQueueOut) must be different"
        }

        // Create PGMQ clients for each queue
        commandsInClient = PgmqClient(PgmqConnectorConfig(config, COMMANDS_IN_CHANNEL))
        commandsOutClient = PgmqClient(PgmqConnectorConfig(config, COMMANDS_OUT_CHANNEL))
        eventsInClient = PgmqClient(PgmqConnectorConfig(config, EVENTS_IN_CHANNEL))
        eventsOutClient = PgmqClient(PgmqConnectorConfig(config, EVENTS_OUT_CHANNEL))

        // Initialize queues once
        runBlocking {
            commandsInClient.initialize()
            commandsOutClient.initialize()
            eventsInClient.initialize()
            eventsOutClient.initialize()
        }
    }

    @AfterAll
    fun closeClients() {
        if (::commandsInClient.isInitialized) commandsInClient.close()
        if (::commandsOutClient.isInitialized) commandsOutClient.close()
        if (::eventsInClient.isInitialized) eventsInClient.close()
        if (::eventsOutClient.isInitialized) eventsOutClient.close()
    }

    override fun setupMessaging() {
        // Purge queues to ensure clean state between tests
        runBlocking {
            commandsInClient.purge()
            commandsOutClient.purge()
            eventsInClient.purge()
            eventsOutClient.purge()
        }
    }

    override fun cleanupMessaging() {
        // No-op - clients are closed in @AfterAll
    }

    override fun sendCommand(message: String) {
        runBlocking {
            commandsInClient.send(message)
        }
    }

    override suspend fun receiveCommand(timeout: Long, unit: TimeUnit): String? {
        val timeoutMs = unit.toMillis(timeout)
        val pollIntervalMs = 100
        val maxPollSeconds = (timeoutMs / 1000).coerceAtLeast(1).toInt()

        // Use long polling to efficiently wait for messages
        val messages = commandsOutClient.readWithPoll(
            batchSize = 1,
            maxPollSeconds = maxPollSeconds,
            pollIntervalMs = pollIntervalMs
        )

        return messages.firstOrNull()?.let { msg ->
            // Delete the message to acknowledge it
            commandsOutClient.delete(msg.msgId)
            msg.message
        }
    }

    override suspend fun receiveEvent(timeout: Long, unit: TimeUnit): String? {
        val timeoutMs = unit.toMillis(timeout)
        val pollIntervalMs = 100
        val maxPollSeconds = (timeoutMs / 1000).coerceAtLeast(1).toInt()

        // Use long polling to efficiently wait for messages
        val messages = eventsOutClient.readWithPoll(
            batchSize = 1,
            maxPollSeconds = maxPollSeconds,
            pollIntervalMs = pollIntervalMs
        )

        return messages.firstOrNull()?.let { msg ->
            // Delete the message to acknowledge it
            eventsOutClient.delete(msg.msgId)
            msg.message
        }
    }
}
