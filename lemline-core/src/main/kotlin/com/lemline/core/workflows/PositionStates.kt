// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeNavigator.findNodeByReference
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.NodeState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable

@ExperimentalTime
@Serializable
@JvmInline
value class PositionStates(private val states: Map<NodePosition, NodeState>) {

    operator fun get(key: NodePosition): NodeState? = states[key]

    companion object Companion {
        fun fromJsonString(jsonString: String) = LemlineJson.decodeFromString<PositionStates>(jsonString)
    }

    fun toJsonString() = LemlineJson.encodeToString(states)


    fun toNodeStates(rootNode: Node<*>) =
        states.mapKeys { findNodeByReference(it.key.toString(), rootNode) }.toMutableMap()
}
