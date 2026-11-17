// SPDX-License-Identifier: BUSL-1.1
@file:kotlinx.serialization.UseSerializers(
    com.lemline.common.json.JsonNodeSerializer::class,
    com.lemline.common.json.ObjectNodeSerializer::class,
    com.lemline.common.json.ArrayNodeSerializer::class,
)

package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.core.processors.ListenStrategy
import io.cloudevents.CloudEvent
import kotlinx.serialization.Serializable

/** Per-subscription progress for the one/any/all strategy used by until. */
@Serializable
data class EventConsumptionProgress(
    val matched: MutableSet<Int> = mutableSetOf(),
    val correlations: MutableMap<String, String> = mutableMapOf(),
) {
    fun consume(rule: CachedEventConsumption, event: CloudEvent, context: JsonNode?): Boolean {
        // DSL 1.0 forbids another until inside the termination strategy.
        require(rule.untilExpression == null && rule.untilEvents == null) { "Nested until conditions are not supported by DSL 1.0" }
        val indices = rule.filters.mapIndexedNotNull { index, filter ->
            if (rule.strategy == ListenStrategy.ALL && index in matched) return@mapIndexedNotNull null
            if (filter.matches(event, correlations, context)) index else null
        }
        if (indices.isEmpty() && !rule.wildcard) return false
        matched.addAll(indices)
        return when (rule.strategy) {
            ListenStrategy.ONE, ListenStrategy.ANY -> true
            ListenStrategy.ALL -> matched.size == rule.filters.size
        }
    }
}
