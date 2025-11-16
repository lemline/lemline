// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.ForkException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.BranchState
import com.lemline.core.states.ForkTaskState
import io.serverlessworkflow.api.types.ForkTask
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node processor for ForkTask (parallel/concurrent execution).
 *
 * Fork executes multiple branches either:
 * - Cooperatively (compete: false): All branches must complete, returns array of outputs
 * - Competitively (compete: true): First branch to complete wins, returns single output
 *
 * ## Execution Model
 *
 * Fork follows the ChildWorkflow pattern:
 * 1. First entry: Throw ForkException with branch configuration
 * 2. Orchestrator catches exception:
 *    - ExecutionMode.Complete: Execute branches in parallel using coroutines
 *    - ExecutionMode.Async: Return WorkflowState.RunningFork for runner
 * 3. Branches execute as independent workflows (can pause/resume)
 * 4. On branch completion: Re-enter fork processor with branch output
 * 5. Check if fork complete (based on compete mode)
 * 6. Either continue to next branch or complete fork
 *
 * ## Example Workflow
 *
 * ```yaml
 * raiseAlarm:
 *   fork:
 *     compete: true  # First responder wins
 *     branches:
 *       - callNurse:
 *           call: http
 *           with:
 *             endpoint: /alert/nurse
 *       - callDoctor:
 *           call: http
 *           with:
 *             endpoint: /alert/doctor
 * ```
 *
 * @property node Immutable ForkTask definition
 */
class ForkProcessor(
    node: Node<ForkTask>
) : NodeProcessor<ForkTask, ForkTaskState>(node) {

    private val forkConfig = node.task.fork
    // Access compete - try both field and method access
    private val compete: Boolean = run {
        try {
            // Try direct field access first (Kotlin data class style)
            try {
                val field = forkConfig.javaClass.getDeclaredField("compete")
                field.isAccessible = true
                val result = field.get(forkConfig) as? Boolean ?: false
                logger.debug { "Fork compete mode (via field): $result" }
                return@run result
            } catch (e: NoSuchFieldException) {
                // Field doesn't exist, try method access
            }

            // Try method access with various naming conventions
            val methods = listOf("compete", "getCompete", "isCompete")
            for (methodName in methods) {
                try {
                    val method = forkConfig.javaClass.getMethod(methodName)
                    val result = method.invoke(forkConfig) as? Boolean ?: false
                    logger.debug { "Fork compete mode (via $methodName): $result" }
                    return@run result
                } catch (e: NoSuchMethodException) {
                    // Try next method name
                }
            }

            logger.warn { "Could not access compete field, defaulting to false (cooperative mode)" }
            false
        } catch (e: Exception) {
            logger.warn(e) { "Failed to access compete field, defaulting to false" }
            false
        }
    }
    private val branchCount: Int = node.children?.size ?: 0

    override fun createState(transformedInput: JsonElement, scope: Scope): ForkTaskState {
        return ForkTaskState(
            startedAt = Clock.System.now(),
            branchStates = (0 until branchCount).associateWith { BranchState.PENDING },
            branchOutputs = emptyMap()
        )
    }

    override fun getNextStepInfo(
        state: ForkTaskState,
        dataset: JsonElement,
        scope: Scope,
        namedNode: String?
    ): NextStepInfo {
        logger.debug { "ForkProcessor.getNextStepInfo: branchStates=${state.branchStates}, branchOutputs=${state.branchOutputs}" }

        // First entry: All branches are PENDING
        // Throw exception to trigger fork execution via orchestrator
        if (state.branchStates.values.all { it == BranchState.PENDING }) {
            logger.debug { "All branches PENDING, throwing ForkException" }
            throw ForkException(
                transformedInput = dataset,
                config = ForkException.Config(
                    compete = compete,
                    branches = node.children!!.mapIndexed { index, child ->
                        ForkException.BranchInfo(
                            index = index,
                            name = child.name,
                            nodePosition = child.position
                        )
                    }
                )
            )
        }

        // Re-entry: Orchestrator has executed branches and is calling back with assembled output
        // The dataset contains the assembled output (single value or array)
        // The state should already be updated by the orchestrator with completed branches

        // Verify fork is complete
        val isForkComplete = when {
            compete && state.branchOutputs.isNotEmpty() -> true
            !compete && state.branchOutputs.size == branchCount -> true
            else -> false
        }

        if (!isForkComplete) {
            throw IllegalStateException(
                "Fork re-entered without completion: " +
                "compete=$compete, " +
                "completed=${state.branchOutputs.size}/$branchCount"
            )
        }

        // Fork complete - return to parent
        return NextStepInfo(
            updatedState = null,  // Clear state - fork is done
            nextNode = node.parent,
            flowDirective = getFlowDirective()
        )
    }

    /**
     * Execute fork - just returns the assembled output.
     *
     * The transformedInput already contains the assembled output from the orchestrator.
     * This is passed when fork re-enters after branches complete.
     * So we just return it as-is.
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope
    ): JsonElement {
        // The transformedInput already contains the assembled output from orchestrator
        return transformedInput
    }
}
