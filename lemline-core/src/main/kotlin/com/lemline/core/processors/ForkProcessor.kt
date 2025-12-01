// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.ForkState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent.ForkStarted
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
 * Fork follows the async task pattern:
 * 1. First entry: Throw ForkStartedException with branch configuration
 * 2. Runner catches exception and schedules branches for parallel execution
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

    override val isAsync = true

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope) = ForkState()

    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ) = ForkStarted(
        nodeStack = nodeStack,
        rawInput = transformedInput,
    )
}
