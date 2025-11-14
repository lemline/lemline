// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.NodeState
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.Duration
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
 * - The duration before a retry (if any)
 *
 * @property nextNode The next node to execute (null if workflow complete)
 * @property rawInput The dataset to pass to the next node
 * @property stateUpdates State changes to apply (position -> state or null for deletion)
 * @property flowDirective The navigation instruction for the next step (from SDK)
 * @property newContext The context exported by this task (from export.as directive, null if no export)
 */
data class StepResult(
    val nextNode: Node<*>?,
    val rawInput: JsonElement,
    val stateUpdates: Map<NodePosition, NodeState?>,
    val flowDirective: FlowDirective? = null,
    val newContext: JsonObject? = null,
    val delay: Duration? = null
)
