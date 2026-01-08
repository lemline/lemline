// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

import io.cloudevents.CloudEvent
import kotlin.time.ExperimentalTime

/**
 * Interface for emitting workflow lifecycle CloudEvents.
 *
 * Implementations publish lifecycle events (workflow.started, task.completed, etc.)
 * for external observability systems.
 *
 * This follows a fire-and-forget pattern:
 * - Events are sent asynchronously without waiting for acknowledgment
 * - Failures should be logged but never thrown to avoid impacting workflow execution
 *
 * @see CloudEventLifecycleHook for building CloudEvents from workflow state
 */
@ExperimentalTime
interface LifecycleEventEmitter {
    /**
     * Emits a lifecycle CloudEvent.
     *
     * This method should be fire-and-forget:
     * - Returns immediately after queuing the event
     * - Never throws exceptions (logs warnings on failure)
     * - Does not block workflow execution
     *
     * @param cloudEvent The CloudEvent to emit (already includes all attributes and extensions)
     */
    suspend fun emit(cloudEvent: CloudEvent)
}
