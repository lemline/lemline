// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.core.cloudevents.CloudEventMatcher
import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.LemlineJson
import com.lemline.core.processors.EventFilter
import com.lemline.core.utils.convertFilter
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.EventConsumptionStrategy
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Durable collections, in arrival order. Completed collections are removed atomically with the start. */
@Serializable
data class EventScheduleState(val groups: MutableList<EventScheduleGroup> = mutableListOf()) {
    fun consume(strategy: Any, event: CloudEvent): EventScheduleConsumption {
        val completed = mutableListOf<JsonArray>()
        var matchedEvent = false
        val filters = filters(strategy)
        val all = strategy is AllEventConsumptionStrategy
        val until = (strategy as? AnyEventConsumptionStrategy)?.until?.get()
        val eventJson = CloudEventService.serialize(event)

        // An event is assigned to the oldest compatible collection with a free filter slot.
        // This preserves repeated ALL events (A1, A2, B1, B2 => A1+B1, A2+B2).
        val candidates = groups.toList() + EventScheduleGroup()
        for (group in candidates) {
            val correlations = group.correlations.toMutableMap()
            val matched = filters.mapIndexedNotNull { index, filter ->
                if (all && index in group.matched) return@mapIndexedNotNull null
                if (matches(event, filter, correlations)) index else null
            }
            if (matched.isEmpty() && !(strategy is AnyEventConsumptionStrategy && filters.isEmpty())) continue
            matchedEvent = true
            group.correlations.putAll(correlations)
            group.events.add(eventJson)
            group.matched.addAll(matched)
            if (group !in groups) groups.add(group)
            val ready = when {
                all -> group.matched.size == filters.size
                until is String -> CloudEventMatcher.evaluateExpressionAsBoolean(until, group.input())
                until != null -> false
                else -> true
            }
            if (ready) {
                completed.add(group.input())
                groups.remove(group)
            }
            break
        }

        // Termination filters can themselves be ONE, ANY or ALL. Keep their partial matches durable.
        if (until is EventConsumptionStrategy) {
            val termination = until.get()
            val terminationFilters = filters(termination)
            var matchedExisting = false
            // The until subscription is active as soon as the definition is posted, even
            // before the first main event. Preserve early termination progress per correlation.
            for (group in groups.toList() + EventScheduleGroup()) {
                val existing = group in groups
                if (!existing && matchedExisting) break
                val correlations = group.correlations.toMutableMap()
                val matched = terminationFilters.mapIndexedNotNull { index, filter ->
                    if (matches(event, filter, correlations)) index else null
                }
                val wildcard = termination is AnyEventConsumptionStrategy && terminationFilters.isEmpty()
                if (matched.isEmpty() && !wildcard) continue
                matchedEvent = true
                if (existing) matchedExisting = true else groups.add(group)
                group.termination.addAll(matched)
                group.correlations.putAll(correlations)
                val ready = if (termination is AllEventConsumptionStrategy) {
                    group.termination.size == terminationFilters.size
                } else {
                    group.termination.isNotEmpty() || wildcard
                }
                if (ready) {
                    completed.add(group.input())
                    groups.remove(group)
                }
            }
        }
        return EventScheduleConsumption(matchedEvent, completed)
    }

    private fun matches(event: CloudEvent, filter: ScheduleFilter, correlations: MutableMap<String, String>): Boolean {
        // Check extensions before the shared matcher can establish correlation values.
        if (!filter.extensions.all { (name, expected) ->
                val actual = event.getExtension(name) ?: return@all false
                with(LemlineJson) {
                    val actualJson = jacksonMapper.valueToTree<JsonNode>(actual).toJsonElement()
                    if (expected is String && ExpressionUtils.isExpr(expected)) {
                        CloudEventMatcher.evaluateExpressionAsBoolean(expected, actualJson)
                    } else {
                        actualJson == jacksonMapper.valueToTree<JsonNode>(expected).toJsonElement()
                    }
                }
            }) return false
        return CloudEventMatcher.matchesFilters(event, listOf(filter.standard), JsonObject(emptyMap()), correlations)
    }

    private fun filters(strategy: Any): List<ScheduleFilter> = when (strategy) {
        is OneEventConsumptionStrategy -> listOf(strategy.one)
        is AllEventConsumptionStrategy -> strategy.all
        is AnyEventConsumptionStrategy -> strategy.any
        else -> error("Unknown event consumption strategy: ${strategy.javaClass.name}")
    }.map { ScheduleFilter(convertFilter(it), it.with?.additionalProperties.orEmpty()) }

    private data class ScheduleFilter(val standard: EventFilter, val extensions: Map<String, Any?>)
}

data class EventScheduleConsumption(val matched: Boolean, val inputs: List<JsonArray>)

@Serializable
data class EventScheduleGroup(
    val correlations: MutableMap<String, String> = mutableMapOf(),
    val events: MutableList<String> = mutableListOf(),
    val matched: MutableSet<Int> = mutableSetOf(),
    val termination: MutableSet<Int> = mutableSetOf(),
) {
    // DSL 1.0.0 defines the input as an array of triggering CloudEvents.
    fun input() = JsonArray(events.map { CloudEventService.parseEnvelope(CloudEventService.deserialize(it)) })
}
