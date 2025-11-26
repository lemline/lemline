// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.AsyncTaskException.WaitStartedException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.WaitState
import com.lemline.core.utils.toDuration
import io.serverlessworkflow.api.types.WaitTask
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
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

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope) = WaitState()

    /**
     * Execute wait action - returns input unchanged.
     *
     * The wait duration is NOT executed here - it's returned in the StepResult
     * so the orchestrator can decide whether to actually delay or not.
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return The input unchanged (delay handled by orchestrator)
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
        state: WaitState,
    ): JsonElement {
        getDelay()?.let {
            val config = WaitStartedException.Config(waitUntil = Clock.System.now() + it)
            logger.debug { "Throwing WaitException for orchestrator to handle: $config" }
            throw WaitStartedException(state, transformedInput, config)
        }

        logger.debug { "Wait skipped (duration <= 0) for task: ${node.name}" }
        // Just return input - no actual delay here
        return transformedInput
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
    fun getDelay(): Duration? {
        return try {
            val duration = node.task.wait.toDuration()
            logger.debug { "Wait task delay: $duration for task: ${node.name}" }
            if (duration > Duration.ZERO) duration else null
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse wait duration: ${node.task.wait}" }
            throw IllegalArgumentException("Invalid wait duration: ${node.task.wait}. Expected ISO 8601 duration (e.g., 'PT5S')")
        }
    }
}
