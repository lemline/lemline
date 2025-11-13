// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.nodes.Node
import com.lemline.core.workflows.PositionStates
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject

typealias States = Map<Node<*>, NodeState>

typealias MutableStates = MutableMap<Node<*>, NodeState>

/**
 * Applies a set of state updates to the current `States` map.
 * Updates the state of nodes based on the given map of updates - either inserting, updating, or removing the state for a node.
 *
 * @param stateUpdates A map where the key is a node and the value is the desired state.
 *                     If the state is null, the corresponding node's state is removed.
 */
internal fun MutableStates.updateWith(
    stateUpdates: Map<Node<*>, NodeState?>
) {
    for ((node, state) in stateUpdates) {
        if (state == null) {
            remove(node)  // Delete state
        } else {
            this[node] = state  // Update or insert state
        }
    }
}

/**
 * Replace the context of the root node in the current state tree by a new context.
 */
internal fun MutableStates.replaceContext(context: JsonObject?) {
    if (context == null) return

    val rootNode = keys.first { it.parent == null }

    // Get the current root state (must exist, as root is always entered first)
    when (val rootState = this[rootNode]) {
        null -> throw IllegalStateException("RootState not found for node '${rootNode.name}' - workflow not properly initialized")
        is RootState -> {
            // Replace context with new context (as per Serverless Workflow spec)
            this[rootNode] = rootState.copyWithContext(context)
        }

        else -> throw IllegalStateException("State of root node ${rootNode.reference}, not a RootState: $rootState")
    }
}

@ExperimentalTime
fun States.toPositionStates() = PositionStates(mapKeys { it.key.position })
