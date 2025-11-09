// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.execution.state.DoState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.execution.state.Scope
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node instance for DoTask (sequential execution) - pure functional model.
 *
 * DoTask executes its children sequentially in order. Each child receives
 * the previous child's output as its input.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - validateOrder:
 *       call: http
 *       with:
 *         url: https://api.example.com/validate
 *   - processOrder:
 *       set:
 *         status: "processed"
 *   - notifyCustomer:
 *       emit:
 *         event: "orderProcessed"
 * ```
 *
 * State transitions:
 * 1. Enter: childIndex = 0 (validateOrder)
 * 2. Re-enter: childIndex = 1 (processOrder)
 * 3. Re-enter: childIndex = 2 (notifyCustomer)
 * 4. Re-enter: childIndex = 3 (>= children.size, exit)
 *
 * @property node Immutable DoTask definition
 */
class DoProcessor(
    node: Node<DoTask>
) : NodeProcessor<DoTask, DoState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): DoState = DoState(
        startedAt = Clock.System.now(),
        index = -1
    )

    override fun getNextStepInfo(
        state: DoState,
        dataset: JsonElement,
        nodeName: String?,
        scope: Scope,
    ): Triple<NodeState?, Node<*>?, FlowDirective?> {
        val nextIndex = getNextIndex(state, nodeName)
        val updatedState = state.copy(index = nextIndex)
        return when (nextIndex >= (node.children?.size ?: 0)) {
            true -> Triple(null, node.parent, getFlowDirective())
            false -> Triple(updatedState, node.children?.get(nextIndex), null)
        }
    }

    private fun getNextIndex(state: DoState, name: String?): Int = when (name) {
        null -> state.index + 1
        else -> node.children?.indexOfFirst { it.name == name } ?: throw NoSuchElementException()
    }
}
