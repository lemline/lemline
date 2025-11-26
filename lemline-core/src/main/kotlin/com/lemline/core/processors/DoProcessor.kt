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

    override fun createState(transformedInput: JsonElement, scope: Scope): DoState = DoState(
        startedAt = Clock.System.now(),
        index = -1
    )

    override fun getNextStepInfo(
        state: DoState,
        dataset: JsonElement,
        scope: Scope,
        namedNode: String?,
    ): NextStepInfo<DoState> {
        val nextIndex = getNextIndex(state, namedNode)
        // Increment visitCount when re-entering from child (going up in the tree)
        val updatedState = state.copy(
            index = nextIndex,
            visitCount = state.visitCount + 1
        )
        return when (nextIndex >= (node.children?.size ?: 0)) {
            true -> NextStepInfo(
                updatedState = updatedState,
                nextNode = node.parent,
                nextDirective = getFlowDirective()
            )

            false -> NextStepInfo(
                updatedState = updatedState,
                nextNode = getChildByIndex(nextIndex),
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
