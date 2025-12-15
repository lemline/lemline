// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-memory CloudEventHook for testing.
 *
 * Uses a buffered channel so events emitted BEFORE a listener subscribes
 * are queued and delivered when the listener starts. This enables simple
 * test setup without timing coordination.
 *
 * ## Usage
 *
 * ```kotlin
 * val cloudEventHook = InMemoryCloudEventHook()
 *
 * // Emit events BEFORE starting the workflow - they're buffered
 * cloudEventHook.emit(orderCreatedEvent)
 *
 * // Workflow's listen task will receive the buffered event
 * val result = FullOrchestrator.start(
 *     workflow = workflow,
 *     cloudEventHook = cloudEventHook,
 *     lifecycleHook = LifecycleEventHook.NOOP,
 *     activityExecutor = MockActivityExecutor.empty()
 * )
 *
 * // Assert on emitted events (includes workflow emit tasks)
 * assertEquals(1, cloudEventHook.emittedEvents.size)
 * ```
 *
 * ## Closing the Hook
 *
 * Call `close()` to complete any active receive() flows. This unblocks
 * listeners waiting for events (e.g., if a test doesn't emit expected events):
 *
 * ```kotlin
 * cloudEventHook.close()  // Completes the flow, unblocking listeners
 * ```
 *
 * ## Thread Safety
 *
 * This implementation is thread-safe. Events are queued in a Channel.
 */
@ExperimentalTime
class InMemoryCloudEventHook : CloudEventHook {

    private val logger = logger()

    // Channel with unlimited buffer - events emitted before consumption are queued
    private val channel = Channel<CloudEvent>(Channel.UNLIMITED)

    // Track emitted events for test assertions
    private val _emittedEvents = mutableListOf<CloudEvent>()

    /**
     * List of all emitted CloudEvents (for test assertions).
     */
    val emittedEvents: List<CloudEvent>
        get() = synchronized(_emittedEvents) { _emittedEvents.toList() }

    override suspend fun emit(event: CloudEvent) {
        logger.debug { "InMemory: Emitting CloudEvent type=${event.type} source=${event.source}" }
        synchronized(_emittedEvents) {
            _emittedEvents.add(event)
        }
        channel.send(event)
    }

    override fun receive(): Flow<CloudEvent> {
        logger.debug { "InMemory: Creating receiver flow" }
        return channel.receiveAsFlow()
    }

    /**
     * Close the channel, completing any active receive() flows.
     * Call this to unblock listeners that are waiting for events.
     */
    fun close() {
        logger.debug { "InMemory: Closing channel" }
        channel.close()
    }

    /**
     * Clear emitted events history (for test setup/teardown).
     */
    fun clearHistory() {
        synchronized(_emittedEvents) {
            _emittedEvents.clear()
        }
    }
}
