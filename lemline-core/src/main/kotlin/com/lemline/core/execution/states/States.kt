// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.states

import com.lemline.core.nodes.Node

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
