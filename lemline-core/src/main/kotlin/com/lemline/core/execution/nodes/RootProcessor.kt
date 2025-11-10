// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.execution.state.RootState
import com.lemline.core.execution.state.Scope
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import java.util.*
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
) : NodeProcessor<RootTask, RootState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): RootState = RootState(
        startedAt = Clock.System.now(),
        id = UUID.randomUUID().toString(),
        input = transformedInput,
        hasRun = false,
    )

    override fun getNextStepInfo(
        state: RootState,
        dataset: JsonElement,
        nodeName: String?,
        scope: Scope,
    ): NextStepInfo = when (state.hasRun) {
        true -> NextStepInfo(
            updatedCurrentState = null,
            nextNode = null,
            flowDirective = null
        )

        false -> {
            val updatedState = state.copy(hasRun = true)
            NextStepInfo(
                updatedCurrentState = updatedState,
                nextNode = getDoNode(),
                flowDirective = null
            )
        }
    }

    private fun getDoNode() = node.children?.getOrNull(0) ?: throw NoSuchElementException("RootTask has no do task")
}
