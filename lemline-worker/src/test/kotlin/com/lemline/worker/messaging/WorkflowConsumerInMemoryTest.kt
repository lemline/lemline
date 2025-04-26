// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.messaging

import com.lemline.worker.messaging.bases.WorkflowConsumerTest
import com.lemline.worker.tests.profiles.H2InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag

/**
 * In-memory implementation of [WorkflowConsumerTest] for local testing.
 *
 * This test class uses simple in-memory queues to simulate message passing between
 * the workflow consumer and its test environment. It's particularly useful for:
 * - Quick local development testing without external dependencies
 * - CI/CD pipelines where external message brokers might not be available
 * - Fast feedback during development
 *
 * The implementation uses [LinkedBlockingQueue] to provide thread-safe message passing
 * and blocking operations for message sending and receiving.
 */
@QuarkusTest
@TestProfile(H2InMemoryProfile::class)
@Tag("integration")
internal class WorkflowConsumerInMemoryTest : WorkflowConsumerTest() {

    // In-memory queues for message passing
    private val inputQueue = LinkedBlockingQueue<String>()
    private val outputQueue = LinkedBlockingQueue<String>()

    /**
     * Sets up the in-memory messaging infrastructure.
     *
     * This implementation:
     * 1. Clears any existing messages from the queues
     * 2. Initializes the queues for fresh testing
     */
    override fun setupMessaging() {
        // Clear any existing messages
        inputQueue.clear()
        outputQueue.clear()
    }

    /**
     * Cleans up the in-memory messaging infrastructure.
     *
     * In this implementation, we simply clear the queues to ensure
     * no messages remain between test runs.
     */
    override fun cleanupMessaging() {
        inputQueue.clear()
        outputQueue.clear()
    }

    /**
     * Sends a message to the workflow consumer.
     *
     * @param message The message to send
     */
    override fun sendMessage(message: String) {
        // Add the message to the input queue
        inputQueue.put(message)
    }

    /**
     * Receives a message from the workflow consumer.
     *
     * @param timeout The maximum time to wait for a message
     * @param unit The time unit of the timeout
     * @return The received message, or null if no message was received within the timeout
     */
    override fun receiveMessage(timeout: Long, unit: TimeUnit): String? {
        return outputQueue.poll(timeout, unit)
    }
} 
