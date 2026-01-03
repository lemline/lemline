// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.merge
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.buildJsonObject

/**
 * A single frame in the node stack.
 *
 * @property position The node's position in the workflow tree
 * @property state The node's execution state
 * @property executionIndex Increments on re-entry (loops, retries, goto). Used for idempotent ID derivation.
 */
@Serializable
data class StackFrame(
    val position: NodePosition,
    val state: NodeState,
    val executionIndex: Int = 0
) {
    fun withIncrementedIndex() = copy(executionIndex = executionIndex + 1)

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
@Serializable(with = NodeStackSerializer::class)
class NodeStack internal constructor(
    private val frames: List<StackFrame> = emptyList()
) {

    val rootState: RootState by lazy {
        frames.first().state as RootState
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
        frames.joinToString("-") { it.executionIndex.toString() }
    }

    operator fun get(position: NodePosition): NodeState? =
        frames.firstOrNull { it.position == position }?.state


    fun deriveIdempotentId(suffix: String = ""): IDV7 =
        IDV7.deriveIdempotentId(
            baseId = rootState.workflowId.value,
            position = currentPosition,
            executionKey = executionKey,
            suffix = suffix
        )

    /** Creates a new TaskStack with updated context in the root state.*/
    fun setContext(newContext: Scope): NodeStack = withRootState(rootState.withContext(newContext))

    /** Creates a new TaskStack with a new root state, replacing the existing one.*/
    fun withRootState(newRoot: RootState): NodeStack =
        NodeStack(listOf(StackFrame(NodePosition.root, newRoot)) + frames.drop(1))

    /** Push a new frame onto the stack.*/
    fun push(position: NodePosition, state: NodeState): NodeStack =
        NodeStack(frames + StackFrame(position, state, 0))

    /** Pop the top frame and increment the new top frame's executionIndex.*/
    fun pop(): NodeStack = popFrames(frames.dropLast(1))

    /** Returns a new StateStack with frames up to and including position, incrementing new top's executionIndex.*/
    fun popUntil(position: NodePosition): NodeStack {
        val index = frames.indexOfFirst { it.position == position }
        return if (index < 0) this else popFrames(frames.take(index + 1))
    }

    /** Pops frames after position, replaces the frame at position with new state, and increments its executionIndex.*/
    fun popAndReplace(position: NodePosition, newState: NodeState): NodeStack {
        val index = frames.indexOfFirst { it.position == position }
        if (index < 0) return this
        val newFrame = StackFrame(position, newState, frames[index].executionIndex + 1)
        return NodeStack(frames.take(index) + newFrame)
    }

    private fun popFrames(remaining: List<StackFrame>): NodeStack {
        if (remaining.isEmpty()) return NodeStack(remaining)
        return NodeStack(remaining.dropLast(1) + remaining.last().withIncrementedIndex())
    }

    /** Update the current (top) state in the stack.*/
    fun updateCurrentState(newState: NodeState): NodeStack {
        val currentFrame = frames.last()
        return NodeStack(
            frames.dropLast(1) + StackFrame(
                currentFrame.position,
                newState,
                currentFrame.executionIndex + 1
            )
        )
    }

    fun <R> map(transform: (Map.Entry<NodePosition, NodeState>) -> R): List<R> =
        frames.map { frame ->
            transform(object : Map.Entry<NodePosition, NodeState> {
                override val key: NodePosition = frame.position
                override val value: NodeState = frame.state
            })
        }

    val currentExecutionIndex: Int
        get() = frames.lastOrNull()?.executionIndex ?: 0

    fun incrementCurrentExecutionIndex(): NodeStack {
        if (frames.isEmpty()) return this
        val currentFrame = frames.last()
        val newFrame = currentFrame.copy(executionIndex = currentFrame.executionIndex + 1)
        return NodeStack(frames.dropLast(1) + newFrame)
    }

    fun withCurrentExecutionIndex(index: Int): NodeStack {
        if (frames.isEmpty()) return this
        val currentFrame = frames.last()
        val newFrame = currentFrame.copy(executionIndex = index)
        return NodeStack(frames.dropLast(1) + newFrame)
    }

    internal fun <R> mapFrames(transform: (StackFrame) -> R): List<R> = frames.map(transform)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeStack) return false
        return frames == other.frames
    }

    override fun hashCode(): Int = frames.hashCode()

    override fun toString(): String = "NodeStack(frames=$frames)"

    companion object {
        fun empty(): NodeStack = NodeStack(emptyList())

        fun fromFrames(frames: List<StackFrame>): NodeStack = NodeStack(frames)
    }
}

@Serializable
private data class CompactFrame(
    val p: String,
    val s: NodeState,
    val i: Int = 0
)

@OptIn(ExperimentalSerializationApi::class)
internal object NodeStackSerializer : KSerializer<NodeStack> {

    private val delegateSerializer = ListSerializer(CompactFrame.serializer())

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: NodeStack) {
        var previousPath = ""
        val compactFrames = value.mapFrames { frame ->
            val currentPath = frame.position.toString()
            val suffix = if (previousPath.isEmpty() || previousPath == "/") {
                currentPath.removePrefix("/")
            } else {
                currentPath.removePrefix("$previousPath/")
            }
            previousPath = currentPath
            CompactFrame(p = suffix, s = frame.state, i = frame.executionIndex)
        }
        delegateSerializer.serialize(encoder, compactFrames)
    }

    override fun deserialize(decoder: Decoder): NodeStack {
        val compactFrames = delegateSerializer.deserialize(decoder)

        var currentPosition = NodePosition.root
        val frames = compactFrames.map { compact ->
            currentPosition = if (compact.p.isEmpty()) {
                NodePosition.root
            } else {
                val basePath = if (currentPosition.isRoot) "" else currentPosition.toString()
                NodePosition("$basePath/${compact.p}")
            }
            StackFrame(currentPosition, compact.s, compact.i)
        }

        return NodeStack(frames)
    }
}
