// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.sync

import com.lemline.common.logger.logger
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.TaskStates
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/**
 * Executes Wait tasks (delays and time-based waits).
 *
 * This executor provides suspending functions to handle time-based waits
 * in workflow orchestration, supporting both absolute timestamps and durations.
 */
@ExperimentalTime
internal object WaitSync {

    private val logger = logger()

    /**
     * Processes a wait operation in synchronous mode.
     *
     * @param taskStates Current workflow task states
     * @param waitNode The node containing the Wait task
     * @param transformedInput The transformed input data
     * @param waitUntil The absolute timestamp to wait until
     * @param executionMode The execution mode (must be CONTINUOUS)
     * @return StepResult after completing the wait
     */
    suspend fun execute(
        taskStates: TaskStates,
        waitNode: Node<*>,
        transformedInput: JsonElement,
        waitUntil: Instant,
        executionMode: ExecutionMode
    ): StepResult {
        // In case we change execution modes in the future, we check it here
        if (executionMode != ExecutionMode.CONTINUOUS) {
            throw IllegalStateException("Invalid execution mode: $executionMode")
        }

        // Execute the delay
        executeDelay(waitUntil, "Waiting at node: ${waitNode.name} for")

        // Complete the wait task
        return WorkflowOrchestrator.completeStartedTask(taskStates, waitNode, transformedInput)
    }

    /**
     * Executes a delay until a specified timestamp.
     *
     * If the target time is in the past, this function returns immediately.
     * Otherwise, it suspends the coroutine until the specified time is reached.
     *
     * @param waitUntil The absolute timestamp to wait until
     * @param logMessage A descriptive message for logging the delay operation
     */
    suspend fun executeDelay(waitUntil: Instant, logMessage: String) {
        val delayDuration = waitUntil - Clock.System.now()
        logger.debug { "$logMessage: $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Delay completed" }
    }
}
