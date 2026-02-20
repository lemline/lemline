// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.RootState
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

    // the RootProcessor should not create the root state
    override fun stateWhenEnteringFromParent(
        transformedInput: JsonElement,
        scope: Scope
    ): RootState = throw IllegalStateException("RootProcessor does not create state")

    // RootProcessor doesn't need updateState - root never re-enters from a child
    // The default implementation is sufficient

    override fun getNextNode(
        state: RootState,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo = NavigationInfo(
        nextNode = null,
        nextDirective = null
    )
}
