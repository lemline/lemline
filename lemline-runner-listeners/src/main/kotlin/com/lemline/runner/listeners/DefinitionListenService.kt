// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.cloudevents.CloudEventMatcher
import com.lemline.core.processors.EventFilter
import com.lemline.core.workflows.CachedListenTask
import com.lemline.core.workflows.WorkflowCache
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Represents a workflow definition that may listen to an event.
 * This is the result of matching an event against workflow definitions (no database query).
 */
data class MatchingListenTask(
    val listenTask: CachedListenTask,
    /** Correlation values extracted from the event using 'correlate.from' expressions */
    val correlationValuesJson: String?,
    /** Filter index that matched - relevant for ALL and ANY+until strategies */
    val filterIndex: Int
) {
    // Delegated properties from CachedListenTask for convenience
    val workflowInfo: WorkflowInfo get() = listenTask.workflowInfo
    val nodePosition: NodePosition get() = listenTask.nodePosition

    /** Database-compatible strategy derived from core strategy + until condition. */
    val listenerStrategy: ListenerStrategy
        get() = ListenerStrategy.from(listenTask.strategy, listenTask.until)

    /** Converts this definition match to a query key for listener lookup. */
    fun toQueryKey() = ListenerQueryKey(
        workflowInfo = workflowInfo,
        position = nodePosition,
        correlationValuesJson = correlationValuesJson,
        filterIndex = when (listenerStrategy) {
            ListenerStrategy.ALL -> filterIndex             // 0, 1, 2... per filter
            ListenerStrategy.ONE, ListenerStrategy.ANY -> 0 // Always 0 for uniqueness
            ListenerStrategy.ANY_UNTIL_EXPR,
            ListenerStrategy.ANY_UNTIL_EVENT -> null        // NULL for accumulation
        },
        listenerStrategy = listenerStrategy,
        isFromFunction = listenTask.isFromFunction
    )
}

/**
 * Represents a workflow definition whose termination filter matches an event.
 * Used for ANY + until(event) strategy where a specific event type triggers completion.
 */
data class MatchingListenTaskUntilEvent(
    val listenTask: CachedListenTask
) {
    // Delegated properties from CachedListenTask for convenience
    val workflowInfo: WorkflowInfo get() = listenTask.workflowInfo
    val nodePosition: NodePosition get() = listenTask.nodePosition
    val readAs: ListenTaskConfiguration.ListenAndReadAs get() = listenTask.readAs
    val hasForeach: Boolean get() = listenTask.hasForeach

    /** Converts this termination match to a query key (without correlation - termination doesn't use it). */
    fun toQueryKey() = ListenerQueryKey(
        workflowInfo = workflowInfo,
        position = nodePosition,
        correlationValuesJson = null,
        listenerStrategy = ListenerStrategy.ANY_UNTIL_EVENT,
        isFromFunction = listenTask.isFromFunction
    )
}

/**
 * Service for finding listen tasks and matching CloudEvents against active listeners.
 *
 * This service:
 * - Retrieves listen tasks directly from cached workflow definitions (no database tables)
 * - Matches CloudEvent attributes against event filters
 * - Extracts correlation values from events
 * - Queries active listeners with correlation matching
 *
 * ## Key Design Decisions
 *
 * Listen task definitions are NOT stored in separate database tables.
 * Instead, they are retrieved on-demand from workflow definitions cached in DefinitionCache.
 * This eliminates sync complexity and ensures single source of truth.
 *
 * @see WorkflowCache for workflow definition caching
 * @see ListenerRepository for active listener storage
 */
@ApplicationScoped
class DefinitionListenService {

    private val logger = logger()

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    /**
     * Removes all listeners for a workflow definition.
     * Called when a workflow definition is deleted.
     *
     * @param namespace The workflow namespace
     * @param name The workflow name
     * @param version The workflow version
     * @return Number of listeners removed
     */
    suspend fun removeListeners(
        namespace: WorkflowNamespace,
        name: WorkflowName,
        version: WorkflowVersion
    ): Int {
        logger.debug { "Removing listeners for $namespace/$name:$version" }
        return listenerRepository.deleteByWorkflowDefinition(namespace, name, version)
    }

    /**
     * Finds all listen tasks that match the given CloudEvent and satisfy their defined filters.
     * This method evaluates each listen task's filters against the provided event, including lazy-loading
     * event data for filter evaluation, and returns a list of tasks with matching filters.
     *
     * @param event The incoming CloudEvent to evaluate against the filters of listen tasks.
     * @param eventDataProvider A provider function that, when invoked, supplies the CloudEvent data as a JsonElement.
     * @return A list of MatchingListenTask objects representing the listen tasks that match the event and its data.
     */
    fun findMatchingListenTasks(
        event: CloudEvent,
        eventDataProvider: () -> JsonElement = { CloudEventService.parseData(event) }
    ): List<MatchingListenTask> {
        logger.trace { "Finding matching listen tasks for CloudEvent: $event" }

        val eventData by lazy { eventDataProvider() }

        val matches = WorkflowCache.getAllListenTasks().flatMap { listenTask ->
            if (listenTask.filters.isEmpty()) {
                listOf(
                    MatchingListenTask(
                        listenTask = listenTask,
                        correlationValuesJson = null,
                        filterIndex = 0
                    )
                )
            } else {
                listenTask.filters.mapIndexedNotNull { index, filter ->
                    if (!CloudEventMatcher.matchesFilters(event, listOf(filter)) { eventData }) {
                        return@mapIndexedNotNull null
                    }

                    MatchingListenTask(
                        listenTask = listenTask,
                        correlationValuesJson = extractCorrelationJson(filter, eventData),
                        filterIndex = index
                    )
                }
            }
        }

        logger.debug { "Found ${matches.size} matching listen tasks for event $event" }
        return matches
    }

    fun findMatchingUntilEvents(event: CloudEvent): List<MatchingListenTaskUntilEvent> {
        logger.trace { "Finding matching 'until' for CloudEvent: $event" }

        val matches = WorkflowCache.getAllListenTasks().mapNotNull { listenTask ->
            val terminationFilter = listenTask.untilEventFilter ?: return@mapNotNull null
            if (!CloudEventMatcher.matchesFilters(event, listOf(terminationFilter))) return@mapNotNull null
            MatchingListenTaskUntilEvent(listenTask = listenTask)
        }

        logger.debug { "Found ${matches.size} matching listen tasks 'until' event $event" }
        return matches
    }

    private fun extractCorrelationJson(filter: EventFilter, eventData: JsonElement): String? =
        CloudEventMatcher.extractCorrelationValues(filter, eventData)
            ?.toSortedMap()
            ?.let { Json.encodeToString(it.toMap()) }


}
