// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.messaging

import com.lemline.worker.messaging.bases.WorkflowConsumerTest
import com.lemline.worker.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySink
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag

/**
 * Runs the WorkflowConsumerTest suite against an in-memory broker.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
@Tag("integration")
internal class WorkflowConsumerInMemoryTest : WorkflowConsumerTest() {

    @Inject
    @Any
    private lateinit var connector: InMemoryConnector

    private lateinit var source: InMemorySource<String>
    private lateinit var sink: InMemorySink<String>

    /**
     * Sets up the in-memory messaging infrastructure.
     *
     * This implementation:
     * 1. Gets the source and sink channels
     * 2. Initializes the channels for fresh testing
     */
    override fun setupMessaging() {
        source = connector.source(WORKFLOW_IN)
        sink = connector.sink(WORKFLOW_OUT)
    }

    /**
     * Cleans up the in-memory messaging infrastructure.
     */
    override fun cleanupMessaging() {
        // No cleanup needed for in-memory channels
    }

    /**
     * Sends a message to the workflow consumer.
     *
     * @param message The message to send
     */
    override fun sendMessage(message: String) {
        source.send(message)
    }

    /**
     * Receives a message from the workflow consumer.
     *
     * @param timeout The maximum time to wait for a message
     * @param unit The time unit of the timeout
     * @return The received message, or null if no message was received within the timeout
     */
    override fun receiveMessage(timeout: Long, unit: TimeUnit): String? {
        // Wait for the message to be processed
        Thread.sleep(5)
        // Get the first message from the sink
        return sink.received().firstOrNull()?.payload
    }
} 
