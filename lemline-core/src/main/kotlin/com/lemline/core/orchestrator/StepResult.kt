// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.TaskState
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents the result of a single step or execution attempt of a node in a workflow.
 *
 * This class encapsulates information about the outcome of processing a task node,
 * including the next node to execute, the raw input the node processed, updates
 * to the workflow's state, and directives that dictate further flow transitions.
 *
 * @property nextNode The next node in the workflow to process. Null if there is no next node.
 * @property nextInput The raw JSON input provided for execution of the current node.
 * @property stateUpdates A map containing updates to the state of nodes in the workflow,
 * keyed by their positions, where the value is the new `TaskState` or null if no updates are needed.
 * @property nextDirective An optional directive providing instructions for controlling the
 * next steps in the workflow's execution, such as retry or skip.
 * @property nextContext Optional JSON object representing updated workflow context.
 * Can include new or modified variables based on the currently executed node.
 * @property retryAt An optional timestamp indicating when the current task should be retried.
 * Null if no retry is necessary or applicable.
 */
@ExperimentalTime
data class StepResult(
    val nextNode: Node<*>?,
    val nextInput: JsonElement,
    val stateUpdates: Map<NodePosition, TaskState?>,
    val nextDirective: FlowDirective? = null,
    val nextContext: JsonObject? = null,
    val retryAt: Instant? = null
)
