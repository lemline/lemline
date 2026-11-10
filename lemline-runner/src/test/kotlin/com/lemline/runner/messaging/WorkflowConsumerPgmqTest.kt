// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.destinationFor
import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.pgmq.PgmqClient
import com.lemline.runner.messaging.pgmq.config.PgmqConnectorConfig
import com.lemline.runner.tests.profiles.PgmqProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Runs the WorkflowConsumerTest suite against a PGMQ (PostgreSQL Message Queue) broker.
 *
 * Destination model:
 * - The runner follows namespace `test-in` (see [PgmqProfile]) → it consumes the pgmq
 *   queues `lemline-test-in-commands` / `lemline-test-in-events`, read from
 *   `mp.messaging.incoming.*.queues` (single followed namespace → single queue).
 *   The test SENDS raw commands directly into that queue via SQL.
 * - Emissions carry a per-message queue derived from the workflow's namespace (`test`)
 *   → the test OBSERVES `lemline-test-commands` / `lemline-test-events`, which the
 *   runner does NOT consume. Observed queues are computed via [destinationFor] because
 *   the static `mp.messaging.outgoing.*.queue` property is only a default-namespace
 *   fallback, not what emissions actually use. They are created idempotently in setup
 *   since nothing else creates them before the first emission.
 */
@QuarkusTest
@TestProfile(PgmqProfile::class)
@Tag("integration")
@RequiresDocker
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class WorkflowConsumerPgmqTest : WorkflowConsumerTest() {

    private lateinit var commandsQueueIn: String
    private lateinit var eventsQueueIn: String

    /** Queue where the runner emits commands for workflows of namespace `test`. */
    private val commandsQueueObserved = destinationFor("lemline", "test", DestinationType.COMMANDS)

    /** Queue where the runner emits events for workflows of namespace `test`. */
    private val eventsQueueObserved = destinationFor("lemline", "test", DestinationType.EVENTS)

    /** Single client: all queues live in the same PostgreSQL used by the channels. */
    private lateinit var client: PgmqClient

    @BeforeAll
    fun initClients() {
        val config = ConfigProvider.getConfig()

        // The runner consumes one queue per followed namespace; profile follows a single one
        commandsQueueIn = config.getValue("mp.messaging.incoming.$COMMANDS_IN_CHANNEL.queues", String::class.java)
            .split(",").single().trim()
        eventsQueueIn = config.getValue("mp.messaging.incoming.$EVENTS_IN_CHANNEL.queues", String::class.java)
            .split(",").single().trim()

        require(commandsQueueIn != commandsQueueObserved) {
            "For *testing*, queues In ($commandsQueueIn) and Observed ($commandsQueueObserved) must be different"
        }
        require(eventsQueueIn != eventsQueueObserved) {
            "For *testing*, queues In ($eventsQueueIn) and Observed ($eventsQueueObserved) must be different"
        }

        // Connection settings (host/port/database/credentials) come from the channel config
        client = PgmqClient(PgmqConnectorConfig(config, COMMANDS_IN_CHANNEL))

        // Ensure all queues exist (idempotent). The incoming queues are auto-created by
        // the connector at startup; the observed queues receive their first message only
        // when the runner emits, so the test must create them before observing.
        runBlocking {
            client.ensureQueue(commandsQueueIn)
            client.ensureQueue(eventsQueueIn)
            client.ensureQueue(commandsQueueObserved)
            client.ensureQueue(eventsQueueObserved)
        }
    }

    @AfterAll
    fun closeClients() {
        if (::client.isInitialized) client.close()
    }

    override fun setupMessaging() {
        // Drain queues to ensure clean state between tests
        runBlocking {
            drainQueue(commandsQueueIn)
            drainQueue(eventsQueueIn)
            drainQueue(commandsQueueObserved)
            drainQueue(eventsQueueObserved)
        }
    }

    /** Removes all pending messages from [queue] (read + delete until empty). */
    private suspend fun drainQueue(queue: String) {
        while (true) {
            val messages = client.read(batchSize = 100, queue = queue)
            if (messages.isEmpty()) break
            messages.forEach { client.delete(it.msgId, queue = queue) }
        }
    }

    override fun cleanupMessaging() {
        // No-op - the client is closed in @AfterAll
    }

    override fun sendCommand(message: String) {
        runBlocking {
            client.send(message, queue = commandsQueueIn)
        }
    }

    override suspend fun receiveCommand(timeout: Long, unit: TimeUnit): String? =
        receiveFrom(commandsQueueObserved, timeout, unit)

    override suspend fun receiveEvent(timeout: Long, unit: TimeUnit): String? =
        receiveFrom(eventsQueueObserved, timeout, unit)

    private suspend fun receiveFrom(queue: String, timeout: Long, unit: TimeUnit): String? {
        val timeoutMs = unit.toMillis(timeout)
        val pollIntervalMs = 100
        val maxPollSeconds = (timeoutMs / 1000).coerceAtLeast(1).toInt()

        // Use long polling to efficiently wait for messages
        val messages = client.readWithPoll(
            batchSize = 1,
            maxPollSeconds = maxPollSeconds,
            pollIntervalMs = pollIntervalMs,
            queue = queue,
        )

        return messages.firstOrNull()?.let { msg ->
            // Delete the message to acknowledge it
            client.delete(msg.msgId, queue = queue)
            msg.message
        }
    }
}
