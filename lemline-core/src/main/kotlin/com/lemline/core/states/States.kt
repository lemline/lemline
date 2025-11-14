// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeNavigator.findNodeByReference
import com.lemline.core.nodes.NodePosition
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

typealias States = Map<NodePosition, NodeState>

/**
 * Applies a set of state updates to the current `States` map and optionally replaces the root context.
 * Returns a new States map with the updates applied.
 *
 * @param stateUpdates A map where the key is a node and the value is the desired state.
 *                     If the state is null, the corresponding node's state is removed.
 * @param newContext Optional new context to replace the root node's context with.
 * @return A new States map with the updates applied.
 */
internal fun States.updateWith(
    stateUpdates: Map<NodePosition, NodeState?>,
    newContext: JsonObject?
): States {
    val updatedStates = this.toMutableMap()

    // Apply state updates
    for ((node, state) in stateUpdates) {
        if (state == null) {
            updatedStates.remove(node)  // Delete state
        } else {
            updatedStates[node] = state  // Update or insert state
        }
    }

    // Replace context if needed
    if (newContext != null) {
        val rootNode = NodePosition.root

        when (val rootState = updatedStates[rootNode]) {
            is RootState -> updatedStates[rootNode] = rootState.copyWithContext(newContext)
            else -> throw IllegalStateException("State of root node $rootNode should be a ${RootState::class.simpleName}, instead is $rootState")
        }
    }

    return updatedStates
}

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
