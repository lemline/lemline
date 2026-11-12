// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.core.workflows.CachedEventConsumption
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.cloudevents.CloudEventMatcher
import com.lemline.runner.listeners.CloudEventService
import io.cloudevents.CloudEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/** Durable collections, in arrival order. Completed collections are removed atomically with the start. */
@Serializable
data class EventScheduleState(val groups: MutableList<EventScheduleGroup> = mutableListOf()) {
    fun consume(strategy: CachedEventConsumption, event: CloudEvent): EventScheduleConsumption {
        val completed = mutableListOf<JsonArray>()
        var matchedEvent = false
        val filters = strategy.filters
        val all = strategy.strategy == ListenStrategy.ALL
        val until = strategy.untilExpression
        val eventJson = CloudEventService.serialize(event)

        // An event is assigned to the oldest compatible collection with a free filter slot.
        // This preserves repeated ALL events (A1, A2, B1, B2 => A1+B1, A2+B2).
        val candidates = groups.toList() + EventScheduleGroup()
        for (group in candidates) {
            val correlations = group.correlations.toMutableMap()
            val matched = filters.mapIndexedNotNull { index, filter ->
                if (all && index in group.matched) return@mapIndexedNotNull null
                if (filter.matches(event, correlations)) index else null
            }
            if (matched.isEmpty() && !strategy.wildcard) continue
            matchedEvent = true
            group.correlations.putAll(correlations)
            group.events.add(eventJson)
            group.matched.addAll(matched)
            if (group !in groups) groups.add(group)
            val ready = when {
                all -> group.matched.size == filters.size
                until is String -> CloudEventMatcher.evaluateExpressionAsBoolean(until, group.input())
                strategy.untilEvents != null -> false
                else -> true
            }
            if (ready) {
                completed.add(group.input())
                groups.remove(group)
            }
            break
        }

        // Termination filters can themselves be ONE, ANY or ALL. Keep their partial matches durable.
        strategy.untilEvents?.let { termination ->
            val terminationFilters = termination.filters
            var matchedExisting = false
            // The until subscription is active as soon as the definition is posted, even
            // before the first main event. Preserve early termination progress per correlation.
            for (group in groups.toList() + EventScheduleGroup()) {
                val existing = group in groups
                if (!existing && matchedExisting) break
                val correlations = group.correlations.toMutableMap()
                val matched = terminationFilters.mapIndexedNotNull { index, filter ->
                    if (filter.matches(event, correlations)) index else null
                }
                val wildcard = termination.wildcard
                if (matched.isEmpty() && !wildcard) continue
                matchedEvent = true
                if (existing) matchedExisting = true else groups.add(group)
                group.termination.addAll(matched)
                group.correlations.putAll(correlations)
                val ready = if (termination.strategy == ListenStrategy.ALL) {
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
