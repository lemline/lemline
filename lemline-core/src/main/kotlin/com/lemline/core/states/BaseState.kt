// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.orchestrator.context.Scope
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class BaseState {
    /**
     * When the node started execution.
     * Common to all state types.
     */
    @ExperimentalTime
    abstract val startedAt: Instant

    /**
     * Number of times this node has been visited during execution.
     * Increments each time control flow returns to the parent after executing a child.
     * Used to build unique WorkflowStep identifiers for database operations.
     *
     * Examples:
     * - Sequential tasks: increments as each task completes
     * - Loops: increments with each iteration
     * - Retries: increments with each retry attempt
     */
    abstract val visitCount: Int

    @Transient
    open val scope: Scope = JsonObject(mapOf())
}
