// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.AsyncTaskException
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.scope.Scope
import com.lemline.core.states.ForkState
import io.serverlessworkflow.api.types.ForkTask
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
) : NodeProcessor<ForkTask, ForkState>(node) {

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope) = ForkState()

    // ForkProcessor doesn't need updateState - it throws exception on first entry
    // The default implementation is enough

    override fun getNextNode(
        state: ForkState,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo {
        // First entry
        // Throws exception to trigger fork execution via orchestrator
        // The orchestrator will derive fork config from the current Node<ForkTask>
        throw AsyncTaskException.ForkStartedException(state = state, transformedInput = dataset)
    }
}
