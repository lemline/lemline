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
    private fun queryKeys(strategy: CachedEventConsumption, event: CloudEvent, termination: Boolean = false): List<EventScheduleQueryKey> {
        if (strategy.wildcard) return listOf(EventScheduleQueryKey(emptyMap()))
        return strategy.filters.mapIndexedNotNull { index, filter ->
            val correlations = mutableMapOf<String, String>()
            if (!filter.matches(event, correlations)) null else EventScheduleQueryKey(
                correlations,
                availableFilter = index.takeIf { !termination && strategy.strategy == ListenStrategy.ALL },
            )
        }
    }

    suspend fun consume(stateId: IDV7, strategy: CachedEventConsumption, event: CloudEvent, conn: Connection): EventScheduleConsumption {
        val keys = queryKeys(strategy, event) + strategy.untilEvents?.let { queryKeys(it, event, termination = true) }.orEmpty()
        if (keys.isEmpty()) return EventScheduleConsumption(false, emptyList())
        val groups = repository.findCandidateCollections(stateId, keys.distinct(), conn).toMutableList()
        // Candidate filtering can omit the newest collection; allocate against all rows under the lock.
        var nextIndex = repository.nextCollectionIndex(stateId, conn)
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
