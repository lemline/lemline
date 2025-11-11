// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.processors

import com.lemline.core.execution.context.Scope
import com.lemline.core.execution.states.NoState
import com.lemline.core.nodes.Node
import com.lemline.core.utils.toDuration
import io.serverlessworkflow.api.types.WaitTask
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
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
) : NodeProcessor<WaitTask, NoState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): NoState = NoState()

    /**
     * Execute wait action by delaying for the specified duration.
     *
     * The wait duration is evaluated and converted to a Kotlin Duration, then
     * the coroutine is suspended for that period using kotlinx.coroutines.delay().
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return The input unchanged after the delay
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement {
        logger.debug { "Executing wait task: ${node.name}" }

        // Get the wait duration from the task definition using the toDuration extension
        val waitDuration = try {
            node.task.wait.toDuration()
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse wait duration: ${node.task.wait}" }
            throw IllegalArgumentException("Invalid wait duration: ${node.task.wait}. Expected ISO 8601 duration (e.g., 'PT5S')")
        }

        logger.debug { "Waiting for duration: $waitDuration" }

        // Suspend execution for the specified duration
        delay(waitDuration)

        logger.debug { "Wait completed for task: ${node.name}" }

        // Return the input unchanged
        return transformedInput
    }
}
