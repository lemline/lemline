// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.execution.state.DoState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.execution.state.Scope
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node processor for RootTask (workflow root) - pure functional model.
 *
 * RootTask represents the workflow root and executes its children sequentially.
 * It behaves identically to DoTask - executing children in order where each child
 * receives the previous child's output as its input.
 *
 * ## Example Workflow
 *
 * ```yaml
 * document:
 *   dsl: '1.0.0'
 *   namespace: example
 *   name: my-workflow
 *   version: 1.0.0
 * do:
 *   - step1:
 *       set:
 *         value: 10
 *   - step2:
 *       set:
 *         result: ${ .value * 2 }
 * ```
 *
 * State transitions:
 * 1. Enter: childIndex = 0 (step1)
 * 2. Re-enter: childIndex = 1 (step2)
 * 3. Re-enter: childIndex = 2 (>= children.size, exit)
 *
 * @property node Immutable RootTask definition
 */
class RootProcessor(
    node: Node<RootTask>
) : NodeProcessor<RootTask, DoState>(node) {

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
