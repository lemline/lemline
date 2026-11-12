// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.IDV7
import com.lemline.core.cloudevents.CloudEventMatcher
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.workflows.CachedEventConsumption
import io.cloudevents.CloudEvent
import java.sql.Connection
import kotlinx.serialization.json.JsonArray

/** Mutates only affected collections; the caller owns the definition lock and transaction. */
class EventScheduleCollector(private val repository: EventScheduleRepository) {
    suspend fun consume(stateId: IDV7, strategy: CachedEventConsumption, event: CloudEvent, conn: Connection): EventScheduleConsumption {
        val groups = repository.loadCollections(stateId, conn).toMutableList()
        var nextIndex = (groups.maxOfOrNull { it.index } ?: -1L) + 1
        val dirty = mutableSetOf<IDV7>()
        val completed = mutableListOf<JsonArray>()
        var matchedEvent = false

        suspend fun add(group: EventScheduleCollection) {
            repository.insertCollection(stateId, group, conn)
            groups.add(group)
        }
        suspend fun complete(group: EventScheduleCollection, input: JsonArray? = null) {
            completed.add(input ?: repository.collectionInput(group.id, conn))
            repository.deleteCollection(group.id, conn)
            groups.remove(group)
            dirty.remove(group.id)
        }

        // Assign the main event to the oldest compatible collection with a free slot.
        for (group in groups.toList() + EventScheduleCollection(index = nextIndex++)) {
            val correlations = group.correlations.toMutableMap()
            val matched = strategy.filters.mapIndexedNotNull { index, filter ->
                if (strategy.strategy == ListenStrategy.ALL && index in group.matched) return@mapIndexedNotNull null
                if (filter.matches(event, correlations)) index else null
            }
            if (matched.isEmpty() && !strategy.wildcard) continue
            matchedEvent = true
            group.correlations.putAll(correlations)
            group.matched.addAll(matched)
            if (groups.none { it.id == group.id }) add(group)
            repository.appendEvent(group.id, group.eventCount, event, conn)
            group.eventCount++
            dirty.add(group.id)
            var input: JsonArray? = null
            val untilExpression = strategy.untilExpression
            val ready = when {
                strategy.strategy == ListenStrategy.ALL -> group.matched.size == strategy.filters.size
                untilExpression != null -> {
                    input = repository.collectionInput(group.id, conn)
                    CloudEventMatcher.evaluateExpressionAsBoolean(untilExpression, input)
                }
                strategy.untilEvents != null -> false
                else -> true
            }
            if (ready) complete(group, input)
            break
        }

        // A termination event may affect several collections, including an empty one.
        strategy.untilEvents?.let { termination ->
            var matchedExisting = false
            for (group in groups.toList() + EventScheduleCollection(index = nextIndex)) {
                val existing = groups.any { it.id == group.id }
                if (!existing && matchedExisting) break
                val correlations = group.correlations.toMutableMap()
                val matched = termination.filters.mapIndexedNotNull { index, filter ->
                    if (filter.matches(event, correlations)) index else null
                }
                if (matched.isEmpty() && !termination.wildcard) continue
                matchedEvent = true
                if (existing) matchedExisting = true
                val changed = group.correlations != correlations || matched.any { it !in group.termination }
                group.correlations.putAll(correlations)
                group.termination.addAll(matched)
                if (!existing) add(group)
                if (changed) dirty.add(group.id)
                val ready = if (termination.strategy == ListenStrategy.ALL) {
                    group.termination.size == termination.filters.size
                } else group.termination.isNotEmpty() || termination.wildcard
                if (ready) complete(group)
            }
        }
        for (group in groups) if (group.id in dirty) repository.updateCollection(group, conn)
        return EventScheduleConsumption(matchedEvent, completed)
    }
}

data class EventScheduleConsumption(val matched: Boolean, val inputs: List<JsonArray>)
