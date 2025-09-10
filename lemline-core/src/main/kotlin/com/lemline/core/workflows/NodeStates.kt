// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
@Serializable
@JvmInline
value class NodeStates(private val state: Map<NodePosition, NodeState>) {

    operator fun get(key: NodePosition): NodeState? = state[key]

    companion object {
        /**
         * Creates a new object describing the initial states of the nodes.
         */
        fun new(rawInput: JsonElement) =
            NodeStates(
                mapOf(
                    NodePosition.root to NodeState(rawInput = rawInput, startedAt = Clock.System.now()),
                ),
            )

        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<NodeStates>(jsonString)
    }

    fun toJsonString() = LemlineJson.encodeToString(state)
}
