// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.core.processors.scope.Scope
import kotlin.time.Clock
import kotlin.time.Instant

data class ForState(
    override val startedAt: Instant = Clock.System.now(),
    val collection: List<JsonNode>,
    val index: Int = -1,
    val forEach: String,
    val forAt: String
) : NodeState() {

    override val scope: Scope
        get() = buildJsonObject {
            // the first element of the collection is removed at each iteration
            if (collection.isNotEmpty()) {
                put(forEach, collection[0])
                put(forAt, jsonValue(index))
            }
        }
}
