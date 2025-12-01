// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WaitState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.WaitStarted
import com.lemline.core.utils.toDuration
import io.serverlessworkflow.api.types.WaitTask
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Node processor for WaitTask - pure functional model.
 *
 * WaitTask pauses workflow execution for a specified duration before proceeding
 * to the next task. It's an activity task (leaf node) with no children.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - waitForDelay:
 *       wait: PT5S  # Wait for 5 seconds
 *   - waitWithExpression:
 *       wait: ${ "PT" + .seconds + "S" }  # Dynamic duration
 * ```
 *
 * ## Wait Configuration
 *
 * The `wait` property specifies the duration to wait using ISO 8601 duration format:
 * - **PT15S**: 15 seconds
 * - **PT1M**: 1 minute
 * - **PT1H**: 1 hour
 * - **P1D**: 1 day
 * - Can also be a DateTimeDescriptor for absolute time or expression evaluation
 *
 * @property node Immutable WaitTask definition
 */
class WaitProcessor(
    node: Node<WaitTask>,
) : NodeProcessor<WaitTask, WaitState>(node) {

    override val isAsync = true

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope) = WaitState()

    /**
     * Handles the "started" event for a workflow by creating a `WaitStarted` event with
     * the appropriate wait configuration.
     *
     * @param nodeStack The current stack of workflow nodes representing the execution state.
     * @param transformedInput The input data transformed for use in this workflow state.
     * @param scope The current scope of the workflow execution, providing environmental context.
     * @param state The state object representing the current call context for the workflow.
     * @return A `WorkflowEvent` representing the starting state of this segment of the workflow.
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
        val config = WaitConfig(waitUntil = Clock.System.now() + getDelay())
        logger.debug { "Throwing WaitException for orchestrator to handle: $config" }
        return WaitStarted(
            nodeStack = nodeStack,
            rawOutput = transformedInput,
            config = config,
        )
    }

    /**
     * Get the delay duration for this wait task.
     *
     * This ensures the orchestrator receives the delay duration and can
     * decide whether to execute it (CompleteOrchestrator) or pause (PausableOrchestrator).
     *
     * @return Duration to wait, parsed from the task's wait property
     * @throws IllegalArgumentException if the wait duration is invalid
     */
    fun getDelay(): Duration {
        return try {
            val duration = node.task.wait.toDuration()
            logger.debug { "Wait task delay: $duration for task: ${node.name}" }
            if (duration > Duration.ZERO) duration else Duration.ZERO
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse wait duration: ${node.task.wait}" }
            throw IllegalArgumentException("Invalid wait duration: ${node.task.wait}. Expected ISO 8601 duration (e.g., 'PT5S')")
        }
    }
}

@Serializable
data class WaitConfig(
    @Contextual val waitUntil: Instant
)
