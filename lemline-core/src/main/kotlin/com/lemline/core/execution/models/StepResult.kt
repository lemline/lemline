// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.lemline.core.execution.models

import com.lemline.core.execution.state.NodeState
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.FlowDirective
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Result of a single execution step in the pure functional model.
 *
 * This represents the complete state after executing one step of the workflow:
 * - Which node to execute next (or null if workflow complete)
 * - The dataset to pass to the next node
 * - Delta states (explicit state changes to apply)
 * - The flow directive indicating navigation intent
 * - The exported context (from export.as directive)
 *
 * ## Pure Functional Model
 *
 * The run() function is pure - it takes immutable inputs and returns new values.
 * State changes are explicit via deltaStates map:
 * - Key: NodePosition
 * - Value: NodeState (new/updated state) or null (delete state)
 *
 * ## Usage:
 * ```kotlin
 * val result: StepResult = run(currentNode, dataset, states, flowDirective)
 *
 * // Apply delta states atomically
 * states = applyDelta(states, result.deltaStates)
 *
 * when {
 *     result.next == null -> {
 *         // Workflow complete
 *         return result.dataset  // Final output
 *     }
 *     else -> {
 *         // Continue execution
 *         current = result.next
 *         dataset = result.dataset
 *         flowDirective = result.flowDirective
 *     }
 * }
 * ```
 *
 * @property nextNode The next node to execute (null if workflow complete)
 * @property dataset The dataset to pass to the next node
 * @property stateUpdates State changes to apply (position -> state or null for deletion)
 * @property flowDirective The navigation instruction for the next step (from SDK)
 * @property newContext The context exported by this task (from export.as directive, null if no export)
 */
data class StepResult(
    val nextNode: Node<*>?,
    val dataset: JsonElement,
    val stateUpdates: Map<Node<*>, NodeState?>,
    val flowDirective: FlowDirective?,
    val newContext: JsonObject? = null
)
