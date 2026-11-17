// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.extensions

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.core.nodes.Node
import com.lemline.core.states.ExtensionExecutionState
import com.lemline.core.states.ExtensionPhase
import com.lemline.core.states.ExtensionReturn
import com.lemline.core.states.PreparedTaskResult
import com.lemline.core.states.StackFrame

/** Pure decisions only: invocation data belongs to the stack, never to a processor. */
object ExtensionController {
    fun start(selected: List<ExtensionPlan>, rawInput: JsonNode, input: JsonNode): ExtensionExecutionState {
        val first = selected.firstOrNull { it.before != null }
        return ExtensionExecutionState(
            selectedExtensionIndices = selected.map { it.index },
            phase = if (first == null) ExtensionPhase.BODY else ExtensionPhase.BEFORE,
            activeExtensionIndex = first?.index,
            rawInput = rawInput,
            transformedInput = input,
        )
    }

    fun block(node: Node<*>, state: ExtensionExecutionState): Node<*> {
        val plan = node.extensions.single { it.index == state.activeExtensionIndex }
        return requireNotNull(when (state.phase) {
            ExtensionPhase.BEFORE -> plan.before
            ExtensionPhase.AFTER -> plan.after
            else -> error("Phase ${state.phase} does not await an extension block")
        })
    }

    fun validateReturn(node: Node<*>, frame: StackFrame, returned: ExtensionReturn) {
        require(frame.position == node.position) { "Extension return does not target the current frame" }
        val state = requireNotNull(frame.extension) { "Unexpected extension return" }
        state.validate(frame.state)
        require(block(node, state).position == returned.blockPosition) {
            "Unexpected extension block ${returned.blockPosition} at ${node.position}"
        }
    }

    fun afterBefore(node: Node<*>, state: ExtensionExecutionState): ExtensionExecutionState {
        val next = node.extensions.firstOrNull {
            it.index in state.selectedExtensionIndices && it.index > state.activeExtensionIndex!! && it.before != null
        }
        return state.copy(
            phase = if (next == null) ExtensionPhase.BODY else ExtensionPhase.BEFORE,
            activeExtensionIndex = next?.index,
        )
    }

    fun prepared(
        node: Node<*>,
        state: ExtensionExecutionState,
        result: PreparedTaskResult,
        shortCircuit: Boolean,
    ): ExtensionExecutionState {
        val next = if (shortCircuit) null else node.extensions.lastOrNull {
            it.index in state.selectedExtensionIndices && it.after != null
        }
        return state.copy(
            phase = if (next == null) ExtensionPhase.READY else ExtensionPhase.AFTER,
            activeExtensionIndex = next?.index,
            preparedResult = result,
        )
    }

    fun afterAfter(node: Node<*>, state: ExtensionExecutionState, exit: Boolean): ExtensionExecutionState {
        val next = if (exit) null else node.extensions.lastOrNull {
            it.index in state.selectedExtensionIndices && it.index < state.activeExtensionIndex!! && it.after != null
        }
        return state.copy(
            phase = if (next == null) ExtensionPhase.READY else ExtensionPhase.AFTER,
            activeExtensionIndex = next?.index,
        )
    }
}
