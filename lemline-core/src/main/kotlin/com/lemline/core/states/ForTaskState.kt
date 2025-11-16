// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import io.serverlessworkflow.api.types.ForTask
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
@ExperimentalTime
data class ForTaskState(
    override val startedAt: Instant = Clock.System.now(),
    val collection: List<JsonElement>,
    val index: Int = -1
) : TaskState() {

    @Transient
    lateinit var forEach: String

    @Transient
    lateinit var forAt: String

    override val scope: Scope
        get() = buildJsonObject {
            // the first element of the collection is removed at each iteration
            if (collection.isNotEmpty()) {
                put(forEach, collection[0])
                put(forAt, JsonPrimitive(index))
            }
        }

    companion object Companion {
        fun from(
            node: Node<ForTask>,
            startedAt: Instant,
            collection: List<JsonElement>,
            index: Int
        ) = ForTaskState(startedAt, collection, index).apply {
            forEach = node.task.`for`.each ?: "item"
            forAt = node.task.`for`.at ?: "index"
            return this
        }
    }
}
