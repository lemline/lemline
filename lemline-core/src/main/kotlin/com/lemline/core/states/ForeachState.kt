// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.processors.scope.Scope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@ExperimentalTime
data class ForeachState(
    override val startedAt: Instant = Clock.System.now(),
    val item: JsonElement = JsonNull,
    val index: Int = 0,
    val itemVar: String = "item",
    val indexVar: String = "index"
) : NodeState() {

    override val scope: Scope
        get() = buildJsonObject {
            put(itemVar, item)
            put(indexVar, JsonPrimitive(index))
        }
}
