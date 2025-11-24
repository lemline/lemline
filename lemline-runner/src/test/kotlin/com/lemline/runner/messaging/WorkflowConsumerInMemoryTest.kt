// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.runner.messaging.base.WorkflowConsumerTest
import com.lemline.runner.messaging.commands.COMMANDS_IN_CHANNEL
import com.lemline.runner.messaging.commands.COMMANDS_OUT_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_IN_CHANNEL
import com.lemline.runner.messaging.events.EVENTS_OUT_CHANNEL
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySink
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Tag

/**
 * Runs the WorkflowConsumerTest suite against an in-memory broker.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@Tag("integration")
@ExperimentalTime
@ExperimentalSerializationApi
internal class WorkflowConsumerInMemoryTest : WorkflowConsumerTest() {

    @Inject
    @Any
    private lateinit var connector: InMemoryConnector

    private lateinit var instanceSource: InMemorySource<String>
    private lateinit var instanceSink: InMemorySink<String>

    private lateinit var databaseSource: InMemorySource<String>
    private lateinit var databaseSink: InMemorySink<String>

    /**
     * Sets up the in-memory messaging infrastructure.
     *
     * This implementation:
     * 1. Gets the source and sink channels
     * 2. Initializes the channels for fresh testing
     */
    override fun setupMessaging() {
        instanceSource = connector.source(COMMANDS_IN_CHANNEL)
        instanceSink = connector.sink(COMMANDS_OUT_CHANNEL)


        databaseSource = connector.source(EVENTS_IN_CHANNEL)
        databaseSink = connector.sink(EVENTS_OUT_CHANNEL)
    }

    /**
     * Cleans up the in-memory messaging infrastructure.
     */
    override fun cleanupMessaging() {
        if (::instanceSink.isInitialized) instanceSink.clear()
        if (::databaseSink.isInitialized) databaseSink.clear()
    }

    /**
     * Sends a message to the workflow consumer.
     *
     * @param message The message to send
     */
    override fun sendInstanceMessage(message: String) {
        instanceSource.send(message)
    }

    /**
     * Receives a message from the instance consumer.
     *
     * @param timeout The maximum time to wait for a message
     * @param unit The time unit of the timeout
     * @return The received message, or null if no message was received within the timeout
     */
    override suspend fun receiveInstanceMessage(timeout: Long, unit: TimeUnit): String? {
        // Wait for the message to be processed
        delay(10)
        // Get the first message from the sink
        return instanceSink.received().firstOrNull()?.payload
    }

    /**
     * Receives a message from the database consumer.
     *
     * @param timeout The maximum time to wait for a message
     * @param unit The time unit of the timeout
     * @return The received message, or null if no message was received within the timeout
     */
    override suspend fun receivedEvent(timeout: Long, unit: TimeUnit): String? {
        // Wait for the message to be processed
        delay(10)
        // Get the first message from the sink
        return databaseSink.received().firstOrNull()?.payload
    }
}
