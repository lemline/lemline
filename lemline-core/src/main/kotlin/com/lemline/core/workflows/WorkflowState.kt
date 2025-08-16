package com.lemline.core.workflows

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
@JvmInline
value class WorkflowState(private val state: Map<NodePosition, NodeState>) {
    fun toJsonString(): String = LemlineJson.encodeToString(this)

    operator fun get(key: NodePosition): NodeState? = state[key]

    companion object {
        fun startWith(rawInput: JsonElement, parentId: String? = null, parentIsWaiting: Boolean = false) =
            WorkflowState(
                mapOf(
                    NodePosition.root to NodeState(
                        parent = parentId?.let { NodeState.Parent(it, parentIsWaiting) },
                        rawInput = rawInput,
                        startedAt = Instant.now(),
                    ),
                ),
            )

        fun fromJsonString(jsonString: String): WorkflowState =
            LemlineJson.decodeFromString(jsonString)
    }
}
