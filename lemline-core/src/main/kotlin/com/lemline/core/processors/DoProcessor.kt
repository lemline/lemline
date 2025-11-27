// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.DoState
import io.serverlessworkflow.api.types.DoTask
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

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope): DoState = DoState(
        startedAt = Clock.System.now(),
        index = 0  // Start at first child
    )

    override fun stateEnterFromChild(
        state: DoState,
        output: JsonElement,
        scope: Scope,
        nodeName: String?
    ): DoState = state.copy(
        index = getNextIndex(state, nodeName)
    )

    override fun getNextNode(
        state: DoState,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo {
        // state.index already points to the next child to execute
        return when (state.index >= (node.children?.size ?: 0)) {
            true -> NavigationInfo(
                nextNode = node.parent,
                nextDirective = getFlowDirective()
            )

            false -> NavigationInfo(
                nextNode = getChildByIndex(state.index),
                nextDirective = null
            )
        }
    }

    private fun getNextIndex(state: DoState, name: String?): Int = when (name) {
        null -> state.index + 1
        else -> getChildIndexByName(name)
    }

    private fun getChildByIndex(index: Int): Node<*> = node.children?.getOrNull(index) ?: throw NoSuchElementException(
        "No child with index '$index' found in node ${node.position}"
    )

    private fun getChildIndexByName(name: String): Int {
        val index = node.children?.indexOfFirst { it.name == name } ?: -1
        if (index < 0) {
            throw NoSuchElementException("No child with name '$name' found in node ${node.position}")
        }
        return index
    }
}
