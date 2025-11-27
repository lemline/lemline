// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.nodes.Node
import com.lemline.core.states.NodeStack
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Represents the result of a single step or execution attempt of a node in a workflow.
 *
 * This class encapsulates information about the outcome of processing a task node,
 * including the next node to execute, the raw input the node processed, the complete
 * updated workflow state, and directives that dictate further flow transitions.
 *
 * @property nextNode The next node in the workflow to process. Null if there is no next node.
 * @property nextInput The raw JSON input provided for execution of the current node.
 * @property stateStack The complete updated state stack for all nodes in the workflow.
 * @property nextDirective An optional directive providing instructions for controlling the
 * next steps in the workflow's execution, such as retry or skip.
 * @property retryAt An optional timestamp indicating when the current task should be retried.
 * Null if no retry is necessary or applicable.
 */
@ExperimentalTime
data class StepResult(
    val nodeStack: NodeStack,
    val nextNode: Node<*>?,
    val nextInput: JsonElement,
    val nextDirective: FlowDirective? = null,
    val retryAt: Instant? = null
)
