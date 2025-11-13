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
data class ForState(
    override val startedAt: Instant = Clock.System.now(),
    val collection: List<JsonElement>,
    val index: Int = -1
) : NodeState() {

    @Transient
    lateinit var forEach: String

    @Transient
    lateinit var forAt: String

    override val scope: Scope
        get() = buildJsonObject {
            // Add iteration variables with current values
            if (index >= 0 && index < collection.size) {
                put(forEach, collection[index])
                put(forAt, JsonPrimitive(index))
            }
        }

    fun from(node: Node<ForTask>): ForState {
        forEach = node.task.`for`.each ?: "item"
        forAt = node.task.`for`.at ?: "index"
        return this
    }
}
