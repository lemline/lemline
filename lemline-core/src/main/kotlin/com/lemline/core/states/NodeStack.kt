// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.merge
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.annotations.TestOnly

/**
 * A single frame in the node stack.
 *
 * @property position The node's position in the workflow tree
 * @property state The node's execution state
 * @property counter Increments on re-entry (loops, retries, goto). Used for idempotent ID derivation.
 */
data class StackFrame(
    val position: NodePosition,
    val state: NodeState,
    val counter: Int = 0
) {
    fun increment() = copy(counter = counter + 1)
    fun decrement() = copy(counter = counter - 1)
}

/**
 * A stack-based representation of workflow task states.
 *
 * Each frame in the stack represents a node's state at a specific position in the workflow tree.
 * The stack naturally represents the call hierarchy: root at bottom, current node at top.
 *
 * Benefits over Map-based approach:
 * - Stack semantics match workflow execution (push on enter, pop on exit)
 * - Position is implicit in stack order
 * - Hierarchical state access is natural (iterate from top to bottom)
 *
 * Serialization optimization:
 * - Since the stack is hierarchical, we only store the last segment of each position
 * - Full paths are reconstructed during deserialization
 * - Example: [("/", R), ("/do", D), ("/do/task", T)] → [("", R), ("do", D), ("task", T)]
 */
class NodeStack internal constructor(
    private val frames: List<StackFrame> = emptyList()
) : Iterable<StackFrame> by frames {

    val rootState: RootState by lazy {
        frames.first().state as RootState
    }

    val workflowId: WorkflowId by lazy {
        rootState.workflowId
    }

    val currentPosition: NodePosition by lazy {
        frames.last().position
    }

    val currentState: NodeState by lazy {
        frames.last().state
    }

    val stateScope: Scope by lazy {
        frames.fold(buildJsonObject {}) { acc: Scope, frame ->
            acc.merge(frame.state.scope)
        }
    }

    val executionKey: String by lazy {
        frames.joinToString("-") { it.counter.toString() }
    }

    operator fun get(position: NodePosition): StackFrame? = frames.firstOrNull { it.position == position }

    fun deriveIdempotentId(suffix: String = ""): IDV7 = IDV7.deriveIdempotentId(
        baseId = rootState.workflowId.value,
        position = currentPosition,
        executionKey = executionKey,
        suffix = suffix
    )

    fun incrementTopCounter(): NodeStack = NodeStack(
        frames.dropLast(1) + frames.last().increment()
    )

    fun decrementTopCounter(): NodeStack = NodeStack(
        frames.dropLast(1) + frames.last().decrement()
    )

    fun duplicate(workflowId: WorkflowId): NodeStack = withRootState(rootState.copy(workflowId = workflowId))

    /** Creates a new TaskStack with updated context in the root state.*/
    fun withContext(newContext: Scope): NodeStack = withRootState(rootState.withContext(newContext))

    /** Push a new frame onto the stack.*/
    fun push(position: NodePosition, state: NodeState, executionIndex: Int = 0): NodeStack =
        NodeStack(frames + StackFrame(position, state, executionIndex))

    /** Pop the top frame and increment the new top frame's executionIndex.*/
    fun pop(): NodeStack = when (frames.size) {
        1 -> this
        else -> NodeStack(frames.dropLast(1))
    }

    /** Returns a new StateStack with frames up to and including position, incrementing new top's executionIndex.*/
    fun popUntil(position: NodePosition): NodeStack {
        return NodeStack(frames.take(indexOfFirst(position) + 1))
    }

    /** Update the current (top) state in the stack.*/
    fun updateTopState(newState: NodeState): NodeStack =
        NodeStack(frames.dropLast(1) + frames.last().copy(state = newState))

    private fun indexOfFirst(position: NodePosition): Int {
        val index = frames.indexOfFirst { it.position == position }
        if (index < 0) throw NoSuchElementException("Position $position not found within the stack ${frames.joinToString { it.position.toString() }}.")
        return index
    }

    /** Creates a new TaskStack with a new root state, replacing the existing one.*/
    private fun withRootState(newRoot: RootState): NodeStack =
        NodeStack(listOf(StackFrame(NodePosition.root, newRoot)) + frames.drop(1))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeStack) return false
        return frames == other.frames
    }

    override fun hashCode(): Int = frames.hashCode()

    override fun toString(): String =
        "[" + frames.joinToString { it.position.toString() + "(" + it.counter + ")=>" + it.state.toString() } + "]"

    companion object {
        @TestOnly
        fun fromFrames(frames: List<StackFrame>): NodeStack = NodeStack(frames)
    }
}
