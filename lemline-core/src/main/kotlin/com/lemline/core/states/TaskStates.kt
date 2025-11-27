// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.common.values.NodePosition
import com.lemline.core.orchestrator.scope.Scope
import com.lemline.core.orchestrator.scope.merge
import kotlin.time.ExperimentalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.buildJsonObject

/**
 * Wrapper class for workflow task states that provides convenient access
 * to common operations like scope calculation and step incrementing.
 *
 * This class wraps a Map<NodePosition, BaseState> and delegates all map operations to it,
 * while adding workflow-specific functionality.
 */
@Serializable(with = TaskStatesSerializer::class)
data class TaskStates(
    private val states: Map<NodePosition, BaseState>
) : Map<NodePosition, BaseState> by states {

    /**
     * The combined scope from all states.
     */
    val scope: Scope by lazy {
        states.values.fold(buildJsonObject {}) { acc: Scope, state ->
            acc.merge(state.scope)
        }
    }

    /**
     * Creates a new TaskStates with the workflow step counter incremented.
     * The step counter is used for generating unique database IDs for outbox tables.
     *
     * @return A new TaskStates with workflowStep incremented by 1
     */
    fun incrementStep(): TaskStates {
        val rootState = states[NodePosition.root] as RootState
        return copy(states = states + (NodePosition.root to rootState.copy(workflowStep = rootState.workflowStep + 1)))
    }

    /**
     * Creates a new TaskStates with updated context in the root state.
     *
     * @param newContext The new context to set
     * @return A new TaskStates with the updated context
     */
    fun setContext(newContext: Scope): TaskStates {
        val rootState = states[NodePosition.root] as RootState
        return copy(states = states + (NodePosition.root to rootState.copyWithContext(newContext)))
    }

    /**
     * Creates a new TaskStates with an additional entry.
     */
    operator fun plus(pair: Pair<NodePosition, BaseState?>): TaskStates {
        return if (pair.second == null) {
            copy(states = states - pair.first)
        } else {
            copy(states = states + (pair.first to pair.second!!))
        }
    }

    /**
     * Creates a new TaskStates with additional entries from a map.
     */
    operator fun plus(map: Map<NodePosition, BaseState>): TaskStates {
        return copy(states = states + map)
    }

    /**
     * Creates a new TaskStates with the given position removed.
     */
    operator fun minus(position: NodePosition): TaskStates = copy(states = states - position)

    /**
     * Creates a new TaskStates with multiple positions removed.
     */
    operator fun minus(positions: Set<NodePosition>): TaskStates = copy(states = states - positions)
}

/**
 * Serializer for TaskStates that serializes/deserializes as a Map<NodePosition, BaseState>.
 */
internal object TaskStatesSerializer : KSerializer<TaskStates> {
    private val delegateSerializer = MapSerializer(NodePosition.serializer(), BaseState.serializer())

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: TaskStates) {
        delegateSerializer.serialize(encoder, value.toMap())
    }

    override fun deserialize(decoder: Decoder): TaskStates {
        return TaskStates(delegateSerializer.deserialize(decoder))
    }
}
