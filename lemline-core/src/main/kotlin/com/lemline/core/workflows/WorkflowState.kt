// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
@Serializable
@JvmInline
value class WorkflowState(private val state: Map<NodePosition, NodeState>) {

    operator fun get(key: NodePosition): NodeState? = state[key]

    companion object {
        fun newInstance(rawInput: JsonElement) =
            WorkflowState(
                mapOf(
                    NodePosition.root to NodeState(rawInput = rawInput, startedAt = Clock.System.now()),
                ),
            )
    }
}
