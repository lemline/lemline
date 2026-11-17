// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.core.nodes.ForeachTask
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.ForeachState
import com.lemline.core.workflows.foreachDoBlock
import kotlin.time.Instant

class ForeachProcessor(
    node: Node<ForeachTask>
) : AbstractProcessor<ForeachTask, ForeachState>(node) {

    override fun stateWhenEnteringFromParent(transformedInput: JsonNode, scope: Scope, startedAt: Instant): ForeachState =
        ForeachState(
            startedAt = startedAt,
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
        dataset: JsonNode,
        scope: Scope,
    ): NextInfo = NextInfo(
        nextNode = node.foreachDoBlock,
        nextDirective = null
    )
}
