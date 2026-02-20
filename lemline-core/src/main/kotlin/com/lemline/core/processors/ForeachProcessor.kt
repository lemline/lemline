// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.core.nodes.ForeachTask
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.ForeachState
import com.lemline.core.workflows.foreachDoBlock
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement

class ForeachProcessor(
    node: Node<ForeachTask>
) : NodeProcessor<ForeachTask, ForeachState>(node) {

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope): ForeachState =
        ForeachState(
            startedAt = Clock.System.now(),
            item = transformedInput,
            index = 0,
            itemVar = node.task.itemVar,
            indexVar = node.task.indexVar,
        )

    // Get next node to execute within the foreach do block
    // The foreach processor always navigates to its do block
    // Leaving the foreach is handled by the orchestrator
    override fun getNextNode(
        state: ForeachState,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo = NavigationInfo(
        nextNode = node.foreachDoBlock,
        nextDirective = null
    )
}
