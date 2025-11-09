// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.state

import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.ForTask
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
@ExperimentalTime
data class ForState(
    override val startedAt: Instant = Clock.System.now(),
    val collection: List<JsonElement>? = null,
    val index: Int = -1
) : NodeState() {

    @Transient
    lateinit var forEach: String

    @Transient
    lateinit var forAt: String

    override val scope by lazy {
        buildJsonObject {
            put("for.each", JsonPrimitive(forEach))
            put("for.at", JsonPrimitive(forAt))
            put("for.in", JsonArray(collection!!))
        }
    }

    fun from(node: Node<ForTask>): ForState {
        forEach = node.task.`for`.each ?: "item"
        forAt = node.task.`for`.at ?: "index"
        return this
    }
}
