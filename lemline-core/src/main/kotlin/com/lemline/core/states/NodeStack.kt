// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.common.values.NodePosition
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.merge
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.buildJsonObject

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
 *
 * @property frames The list of (NodePosition, NodeState) pairs representing the state stack.
 *                  First element is root, last element is the deepest active node.
 */
@Serializable(with = NodeStackSerializer::class)
data class NodeStack(
    private val frames: List<Pair<NodePosition, NodeState>> = emptyList()
) {

    val rootState: RootState by lazy {
        frames.first().second as RootState
    }

    /**
     * The current position (top of stack).
     * Returns root position if stack is empty.
     */
    val lastPosition: NodePosition by lazy {
        frames.last().first
    }

    /**
     * The top state of the stack.
     * Returns null if stack is empty.
     */
    val lastState: NodeState by lazy {
        frames.last().second
    }

    /**
     * The combined scope from all states in the stack.
     * Scopes are merged from bottom to top, so deeper nodes can override parent scope values.
     */
    val lastScope: Scope by lazy {
        frames.fold(buildJsonObject {}) { acc: Scope, (_, state) ->
            acc.merge(state.scope)
        }
    }

    /**
     * Get the state at a specific position.
     * Returns null if no state exists at that position.
     */
    operator fun get(position: NodePosition): NodeState? =
        frames.firstOrNull { it.first == position }?.second

    /**
     * Creates a new StateStack with the workflow step counter incremented.
     * The step counter is used for generating unique database IDs for outbox tables.
     *
     * @return A new StateStack with workflowStep incremented by 1
     */
    fun incrementStep(): NodeStack = withRootState(rootState.copy(workflowStep = rootState.workflowStep + 1))

    /**
     * Creates a new TaskStack with updated context in the root state.
     *
     * @param newContext The new context to set
     * @return A new TaskStack with the updated context
     */
    fun setContext(newContext: Scope): NodeStack = withRootState(rootState.copyWithContext(newContext))

    /**
     * Creates a new TaskStack with a new root state, replacing the existing one.
     *
     * @param newRoot The new root state
     * @return A new TaskStack with the updated root
     */
    fun withRootState(newRoot: RootState): NodeStack =
        copy(frames = listOf(NodePosition.root to newRoot) + frames.drop(1))

    /**
     * Push a new frame onto the stack or update an existing frame.
     * If position already exists, updates that frame.
     * If position is null, removes the frame at the given position.
     */
    fun push(pair: Pair<NodePosition, NodeState>): NodeStack = NodeStack(frames + pair)

    /**
     * Pop the top frame from the stack.
     * Returns a new StateStack without the top frame.
     * If the stack is empty, returns an empty stack.
     */
    fun pop(): NodeStack = NodeStack(frames.dropLast(1))

    /**
     * Pop frames until reaching the specified position (exclusive).
     * The frame at the specified position is kept.
     * Returns a new StateStack with frames from root up to and including the position.
     */
    fun popUntil(position: NodePosition): NodeStack {
        val index = frames.indexOfFirst { it.first == position }
        return if (index < 0) this else copy(frames = frames.take(index + 1))
    }

    /**
     * Removes all frames from the stack that are located after
     * the specified position (exclusive). If the specified position
     * does not exist in the stack, the original stack is returned.
     */
    fun popExcluding(position: NodePosition): NodeStack {
        val index = frames.indexOfFirst { it.first == position }
        return if (index < 0) this else copy(frames = frames.take(index))
    }

    /**
     * Map over all entries (for toString and similar operations).
     */
    fun <R> map(transform: (Map.Entry<NodePosition, NodeState>) -> R): List<R> =
        frames.map { (position, state) ->
            transform(object : Map.Entry<NodePosition, NodeState> {
                override val key: NodePosition = position
                override val value: NodeState = state
            })
        }
}

/**
 * Custom serializer for [NodeStack] that optimizes storage by only storing
 * the last segment of each position instead of the full path.
 *
 * During serialization: [("/", R), ("/do", D), ("/do/task", T)] → [("", R), ("do", D), ("task", T)]
 * During deserialization: paths are reconstructed by building up incrementally
 */
@OptIn(ExperimentalSerializationApi::class)
internal object NodeStackSerializer : KSerializer<NodeStack> {

    // Serialize as List<Pair<String, NodeState>> where String is just the segment name
    private val delegateSerializer = ListSerializer(PairSerializer(String.serializer(), NodeState.serializer()))

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: NodeStack) {
        // Convert frames to (segment, state) pairs
        val compactFrames = value.map { entry ->
            entry.key.nodeName to entry.value
        }
        delegateSerializer.serialize(encoder, compactFrames)
    }

    override fun deserialize(decoder: Decoder): NodeStack {
        val compactFrames = delegateSerializer.deserialize(decoder)

        // Reconstruct full positions from segments
        var currentPosition = NodePosition.root
        val frames = compactFrames.map { (segment, state) ->
            currentPosition = if (segment.isEmpty()) {
                NodePosition.root
            } else {
                // Use addName for regular names, but need to handle Token segments too
                NodePosition.parse(currentPosition.toString().let { if (it == "/") "" else it } + "/$segment")
            }
            currentPosition to state
        }

        return NodeStack(frames)
    }
}
