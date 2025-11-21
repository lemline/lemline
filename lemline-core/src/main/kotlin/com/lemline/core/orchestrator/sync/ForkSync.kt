// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.sync

import com.lemline.common.logger.logger
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.ForkState
import com.lemline.core.states.TaskStates
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.utils.mapAwaitAllFailFast
import com.lemline.core.utils.mapAwaitFirstFailSlow
import io.serverlessworkflow.api.types.ForkTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Handles execution of fork tasks (parallel branch execution).
 *
 * This executor manages both compete (race) and cooperative (all) fork modes,
 * coordinating parallel execution of multiple workflow branches.
 */
@ExperimentalTime
internal object ForkSync {

    private val logger = logger()

    /**
     * Processes a fork task execution in synchronous mode.
     *
     * @param taskStates Current workflow task states
     * @param forkNode The node containing the Fork task
     * @param rawInput The input data for fork branches
     * @param executionMode The execution mode (must be CONTINUOUS for sync execution)
     * @return StepResult after completing the fork task
     */
    suspend fun execute(
        taskStates: TaskStates,
        forkNode: Node<*>,
        forkState: ForkState,
        rawInput: JsonElement,
        executionMode: ExecutionMode
    ): StepResult {
        @Suppress("UNCHECKED_CAST")
        forkNode as Node<ForkTask>
        val states = taskStates + (forkNode.position to forkState)

        // In case we change execution modes in the future, we check it here
        if (executionMode != ExecutionMode.CONTINUOUS) {
            throw IllegalStateException("Invalid execution mode: $executionMode")
        }

        val branches = forkNode.children
            ?: throw IllegalStateException("Fork node in ${forkNode.reference} has no branches")

        logger.debug { "Executing fork branches in parallel: ${branches.joinToString { it.name }}" }

        // Execute branches and get the result
        val output = if (forkNode.task.fork.isCompete) {
            executeCompete(states, forkNode, rawInput)
        } else {
            executeCooperative(states, forkNode, rawInput)
        }

        // Complete the fork task - this applies output transformation, export, etc.
        return WorkflowOrchestrator.completeStartedTask(states, forkNode, output)
    }

    /**
     * Execute fork branches in compete mode (race for first completion).
     *
     * Returns the output from the first branch to complete successfully.
     *
     * @param taskStates Current workflow task states
     * @param forkNode The fork node with branches to execute
     * @param rawInput Input data for all branches
     * @return Output from the winning branch
     */
    suspend fun executeCompete(
        taskStates: TaskStates,
        forkNode: Node<ForkTask>,
        rawInput: JsonElement,
    ): JsonElement {
        val branches = forkNode.children
            ?: throw IllegalStateException("Fork node in ${forkNode.reference} has no branches")

        // Get the first success - if all branches failed, the last exception will be rethrown from here
        val output = branches.mapAwaitFirstFailSlow { branchNode ->
            executeBranch(branchNode, taskStates, rawInput)
        }

        logger.debug { "Fork compete winner: output=$output" }

        return output
    }

    /**
     * Execute fork branches in cooperative mode (wait for all).
     *
     * Returns an array containing outputs from all branches.
     *
     * @param taskStates Current workflow task states
     * @param forkNode The fork node with branches to execute
     * @param rawInput Input data for all branches
     * @return JsonArray containing all branch outputs
     */
    suspend fun executeCooperative(
        taskStates: TaskStates,
        forkNode: Node<ForkTask>,
        rawInput: JsonElement,
    ): JsonElement {
        val branches = forkNode.children
            ?: throw IllegalStateException("Fork node in ${forkNode.reference} has no branches")

        // Get all results - If a branch failed, the first exception will be rethrown from here
        val outputs = branches.mapAwaitAllFailFast { branchNode ->
            executeBranch(branchNode, taskStates, rawInput)
        }

        logger.debug { "Fork cooperative: all ${outputs.size} branches completed" }

        return JsonArray(outputs)
    }

    /**
     * Execute a single fork branch.
     *
     * The branch executes until it completes or reaches the fork node again
     * (when resuming from an async operation within the branch).
     *
     * @param branchNode The root node of the branch to execute
     * @param taskStates Current workflow task states
     * @param branchInput Input data for this branch
     * @return Output from the completed branch
     * @throws Exception if the branch fails
     */
    suspend fun executeBranch(
        branchNode: Node<*>,
        taskStates: TaskStates,
        branchInput: JsonElement,
    ): JsonElement {
        logger.debug { "Executing branch: ${branchNode.name}" }

        // resumeFromTask stops as soon as reaching up a fork task
        val result = WorkflowOrchestrator.resumeFromTask(
            taskStates = taskStates,
            node = branchNode,
            rawInput = branchInput,
            flowDirective = null,
            executionMode = ExecutionMode.CONTINUOUS
        )

        return when (result) {
            is WorkflowEvent.ForkBranchCompleted -> result.output

            is WorkflowEvent.ForkBranchFailed -> {
                logger.error { "Branch ${branchNode.name} failed: ${result.error}" }
                throw result.exception
            }

            else -> {
                throw IllegalStateException(
                    "Branch execution returned unexpected state: ${result::class.simpleName} for branch ${branchNode.name}"
                )
            }
        }
    }
}
