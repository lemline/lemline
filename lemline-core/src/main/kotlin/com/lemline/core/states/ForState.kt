// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.orchestrator.context.Scope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
@ExperimentalTime
data class ForState(
    override val startedAt: Instant = Clock.System.now(),
    override val visitCount: Int = 0,
    val collection: List<JsonElement>,
    val index: Int = -1,
    val forEach: String,
    val forAt: String
) : TaskState() {

    override val scope: Scope
        get() = buildJsonObject {
            // the first element of the collection is removed at each iteration
            if (collection.isNotEmpty()) {
                put(forEach, collection[0])
                put(forAt, JsonPrimitive(index))
            }
        }
}
