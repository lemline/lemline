// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import io.cloudevents.CloudEvent
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration

/**
 * Captures and queries CloudEvents emitted during workflow execution.
 *
 * Use this to verify:
 * - Lifecycle events (WorkflowStarted, TaskCompleted, etc.)
 * - Custom events emitted via `emit` tasks
 * - Event ordering and attributes
 *
 * ## Test Isolation
 *
 * CloudEventCapture is **scoped to specific workflow IDs** to ensure test isolation.
 * Events from other tests are automatically filtered out.
 */
interface CloudEventCapture {

    /**
     * Get all captured events in order of emission.
     */
    fun events(): List<CloudEvent>

    /**
     * Get the number of captured events.
     */
    fun size(): Int = events().size

    /**
     * Check if any events were captured.
     */
    fun isEmpty(): Boolean = events().isEmpty()

    /**
     * Check if events were captured.
     */
    fun isNotEmpty(): Boolean = events().isNotEmpty()

    /**
     * Filter events by CloudEvent type.
     */
    fun filterByType(type: String): List<CloudEvent>

    /**
     * Filter events by source URI.
     */
    fun filterBySource(source: URI): List<CloudEvent>

    /**
     * Filter events by source prefix.
     */
    fun filterBySourcePrefix(sourcePrefix: String): List<CloudEvent>

    /**
     * Find events matching a predicate.
     */
    fun find(predicate: (CloudEvent) -> Boolean): List<CloudEvent>

    /**
     * Find the first event matching a predicate.
     */
    fun findFirst(predicate: (CloudEvent) -> Boolean): CloudEvent? = find(predicate).firstOrNull()

    /**
     * Get Lemline lifecycle events only.
     */
    fun lifecycleEvents(): List<CloudEvent>

    /**
     * Get custom events only (emitted via emit task).
     */
    fun customEvents(): List<CloudEvent>

    /**
     * Wait for an event matching the predicate to be captured.
     */
    suspend fun awaitEvent(
        timeout: Duration,
        predicate: (CloudEvent) -> Boolean
    ): CloudEvent?

    /**
     * Wait for an event of a specific type.
     */
    suspend fun awaitEventOfType(
        timeout: Duration,
        type: String
    ): CloudEvent? = awaitEvent(timeout) { it.type == type }

    /**
     * Get the workflow IDs this capture is scoped to.
     */
    fun scopedWorkflowIds(): Set<String>

    /**
     * Add a workflow ID to the capture scope.
     */
    fun addToScope(workflowId: String)

    /**
     * Clear all captured events.
     */
    fun clear()
}

/**
 * Default implementation of CloudEventCapture.
 */
class CloudEventCaptureImpl(
    initialWorkflowIds: Set<String> = emptySet()
) : CloudEventCapture {

    private val events = ConcurrentLinkedQueue<CloudEvent>()
    private val scopedIds = CopyOnWriteArraySet<String>().apply { addAll(initialWorkflowIds) }
    private val waiters = ConcurrentLinkedQueue<EventWaiter>()

    override fun events(): List<CloudEvent> = events.toList()

    override fun filterByType(type: String): List<CloudEvent> =
        events.filter { it.type == type }

    override fun filterBySource(source: URI): List<CloudEvent> =
        events.filter { it.source == source }

    override fun filterBySourcePrefix(sourcePrefix: String): List<CloudEvent> =
        events.filter { it.source?.toString()?.startsWith(sourcePrefix) == true }

    override fun find(predicate: (CloudEvent) -> Boolean): List<CloudEvent> =
        events.filter(predicate)

    override fun lifecycleEvents(): List<CloudEvent> =
        events.filter { LemlineEventTypes.isLifecycleEvent(it.type) }

    override fun customEvents(): List<CloudEvent> =
        events.filter { !LemlineEventTypes.isLifecycleEvent(it.type) }

    override suspend fun awaitEvent(timeout: Duration, predicate: (CloudEvent) -> Boolean): CloudEvent? {
        // Check existing events first
        events.firstOrNull(predicate)?.let { return it }

        // Register waiter
        val waiter = EventWaiter(predicate)
        waiters.add(waiter)

        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeout) {
                waiter.await()
            }
        } finally {
            waiters.remove(waiter)
        }
    }

    override fun scopedWorkflowIds(): Set<String> = scopedIds.toSet()

    override fun addToScope(workflowId: String) {
        scopedIds.add(workflowId)
    }

    override fun clear() {
        events.clear()
    }

    /**
     * Called by CloudEventDispatcher when an event is received.
     */
    internal fun onEvent(event: CloudEvent) {
        events.add(event)

        // Notify waiters
        val iterator = waiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (waiter.matches(event)) {
                waiter.complete(event)
            }
        }
    }

    private class EventWaiter(
        private val predicate: (CloudEvent) -> Boolean
    ) {
        private val channel = kotlinx.coroutines.channels.Channel<CloudEvent>(1)

        fun matches(event: CloudEvent): Boolean = predicate(event)

        fun complete(event: CloudEvent) {
            channel.trySend(event)
        }

        suspend fun await(): CloudEvent = channel.receive()
    }
}
