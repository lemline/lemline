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
import kotlinx.serialization.builtins.MapSerializer
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
    val stateScope: Scope by lazy {
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
     * Derives a deterministic IDV7 for the current execution context.
     *
     * Uses workflowId + position + step to ensure uniqueness across:
     * - Different positions in the workflow (each task has unique position)
     * - Multiple executions of the same position (via step counter for loops/retries)
     * - Parallel fork branches (position contains branch name)
     *
     * @param suffix Optional type discriminator (e.g., "-wait", "-retry", "-parent")
     * @return A deterministic IDV7 unique to this execution context
     */
    fun deriveIdempotentId(suffix: String = ""): IDV7 =
        IDV7.deriveFromPositionAndStep(
            baseId = rootState.workflowId.value,
            position = lastPosition,
            step = rootState.workflowStep,
            suffix = suffix
        )

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
     */
    fun push(pair: Pair<NodePosition, NodeState>): NodeStack = NodeStack(frames + pair)

    /**
     * Pop the top frame from the stack.
     */
    fun pop(): NodeStack = NodeStack(frames.dropLast(1))

    /**
     * Returns a new StateStack with frames from root up to and including the position.
     */
    fun popUntil(position: NodePosition): NodeStack {
        val index = frames.indexOfFirst { it.first == position }
        return if (index < 0) this else NodeStack(frames.take(index + 1))
    }

    /**
     * Returns a new StateStack with frames from root up to and excluding the position.
     */
    fun popExcluding(position: NodePosition): NodeStack {
        val index = frames.indexOfFirst { it.first == position }
        return if (index < 0) this else NodeStack(frames.take(index))
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
 * Custom serializer for [NodeStack] that optimizes storage by storing
 * the relative path suffix from the previous position instead of the full path.
 *
 * Serialization format: [{"": RootState}, {"do": DoState}, {"catch/do": DoState}]
 * Each frame is a single-entry object where the key is the relative path suffix.
 *
 * During serialization: [("/", R), ("/do", D), ("/do/task", T)] → [{"": R}, {"do": D}, {"task": T}]
 * For skipped segments: [("/do/tryBlock", T), ("/do/tryBlock/catch/do", D)] → [{"tryBlock": T}, {"catch/do": D}]
 * During deserialization: paths are reconstructed by appending the suffix to the previous position
 */
@OptIn(ExperimentalSerializationApi::class)
internal object NodeStackSerializer : KSerializer<NodeStack> {

    // Serialize as List<Map<String, NodeState>> where each map has exactly one entry
    private val delegateSerializer = ListSerializer(MapSerializer(String.serializer(), NodeState.serializer()))

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: NodeStack) {
        // Convert frames to single-entry maps {relativeSuffix: state}
        var previousPath = ""
        val compactFrames = value.map { entry ->
            val currentPath = entry.key.toString()
            // Compute the relative suffix by removing the previous path prefix
            val suffix = if (previousPath.isEmpty() || previousPath == "/") {
                currentPath.removePrefix("/")
            } else {
                currentPath.removePrefix("$previousPath/")
            }
            previousPath = currentPath
            mapOf(suffix to entry.value)
        }
        delegateSerializer.serialize(encoder, compactFrames)
    }

    override fun deserialize(decoder: Decoder): NodeStack {
        val compactFrames = delegateSerializer.deserialize(decoder)

        // Reconstruct full positions from relative suffixes
        var currentPosition = NodePosition.root
        val frames = compactFrames.map { singleEntryMap ->
            val entry = singleEntryMap.entries.first()
            val suffix = entry.key
            val state = entry.value
            currentPosition = if (suffix.isEmpty()) {
                NodePosition.root
            } else {
                // Append suffix to current position
                val basePath = if (currentPosition.isRoot) "" else currentPosition.toString()
                NodePosition("$basePath/$suffix")
            }
            currentPosition to state
        }

        return NodeStack(frames)
    }
}
