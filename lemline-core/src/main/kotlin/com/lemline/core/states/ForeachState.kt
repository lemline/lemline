// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.lemline.common.json.*
import com.lemline.core.processors.scope.Scope
import kotlin.time.Clock
import kotlin.time.Instant

data class ForeachState(
    override val startedAt: Instant = Clock.System.now(),
    val item: JsonNode = NullNode.instance,
    val index: Int = 0,
    val itemVar: String = "item",
    val indexVar: String = "index"
) : NodeState() {

    override val scope: Scope
        get() = buildJsonObject {
            put(itemVar, item)
            put(indexVar, jsonValue(index))
        }
}
