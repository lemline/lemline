// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import io.cloudevents.CloudEvent
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow

/**
 * Hook interface for CloudEvent operations (emit and listen).
 *
 * This separates CloudEvent handling from activities (HTTP, script, shell),
 * allowing different implementations:
 * - [InMemoryCloudEventHook] for testing (FullOrchestrator)
 * - Real messaging implementation for production (Runner)
 *
 * The hook is a pure transport layer - filtering and strategy handling
 * is done by the orchestrator based on [com.lemline.core.processors.ListenConfig].
 *
 * @see InMemoryCloudEventHook for test implementation
 */
@ExperimentalTime
interface CloudEventHook {

    /**
     * Emit a CloudEvent (fire-and-forget).
     *
     * @param event The CloudEvent to emit
     */
    suspend fun emit(event: CloudEvent)

    /**
     * Receive CloudEvents as a Flow.
     *
     * Filtering based on ListenConfig is done by the caller (orchestrator).
     * The implementation just provides the raw event stream.
     *
     * @return Flow of CloudEvents
     */
    fun receive(): Flow<CloudEvent>
}
