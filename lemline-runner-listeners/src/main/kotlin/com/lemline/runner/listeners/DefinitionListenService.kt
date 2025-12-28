// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.expressions.JQExpression
import com.lemline.core.workflows.CachedListenTask
import com.lemline.core.workflows.WorkflowCache
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.EventDataschema
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.EventSource
import io.serverlessworkflow.api.types.EventTime
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.net.URI
import java.time.OffsetDateTime
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Represents a workflow definition that may listen to an event.
 * This is the result of matching an event against workflow definitions (no database query).
 */
@ExperimentalTime
@ExperimentalSerializationApi
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
        filterIndex = filterIndex,
        listenerStrategy = listenerStrategy
    )
}

/**
 * Represents a workflow definition whose termination filter matches an event.
 * Used for ANY + until(event) strategy where a specific event type triggers completion.
 */
@ExperimentalTime
@ExperimentalSerializationApi
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
        listenerStrategy = ListenerStrategy.ANY_UNTIL_EVENT
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
@ExperimentalTime
@ApplicationScoped
@ExperimentalSerializationApi
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
        logger.debug { "Finding matching listen tasks for CloudEvent: $event" }

        // Parse event data lazily (only if needed for filter evaluation)
        val eventData by lazy { eventDataProvider() }

        val matches = WorkflowCache.getAllListenTasks().flatMap { listenTask ->
            listenTask.filters.mapIndexedNotNull { index, filter ->
                if (!filterMatches(filter, event, eventDataProvider)) return@mapIndexedNotNull null

                MatchingListenTask(
                    listenTask = listenTask,
                    correlationValuesJson = extractCorrelationJson(filter, eventData),
                    filterIndex = index
                )
            }
        }

        logger.debug { "Found ${matches.size} matching listen tasks for event $event" }
        return matches
    }

    /**
     * Finds listen tasks whose termination filters match a given CloudEvent.
     * The method identifies tasks that are configured to terminate upon receiving
     * a specific type of event and checks if the provided event satisfies those conditions.
     *
     * @param event The incoming CloudEvent to evaluate against the termination filters.
     * @param eventDataProvider A provider function to lazily supply the event's data as a JsonElement.
     * @return A list of MatchingListenTaskUntilEvent objects representing the tasks that match
     *         the provided event based on their termination filters.
     */
    fun findMatchingUntilEvents(
        event: CloudEvent,
        eventDataProvider: () -> JsonElement = { CloudEventService.parseData(event) }
    ): List<MatchingListenTaskUntilEvent> {
        logger.debug { "Finding matching 'until' for CloudEvent: $event" }

        // Parse event data lazily

        val matches = WorkflowCache.getAllListenTasks().mapNotNull { listenTask ->
            // Only check tasks with termination filters
            val terminationFilter = listenTask.untilEventFilter ?: return@mapNotNull null

            // Check if this event matches the termination filter
            if (!filterMatches(terminationFilter, event, eventDataProvider)) return@mapNotNull null

            MatchingListenTaskUntilEvent(listenTask = listenTask)
        }

        logger.debug { "Found ${matches.size} matching listen tasks 'until' event $event" }
        return matches
    }

    /**
     * Extracts correlation data from the given event filter and event data, then serializes it into a JSON string.
     * Utilizes correlation definitions in the provided event filter to extract relevant data from the event.
     * If no correlation values are extracted or an error occurs, the method returns null.
     *
     * @param eventFilter The filter containing correlation definitions to evaluate against the event data.
     * @param eventData The JSON representation of the event data to be evaluated.
     * @return A JSON string representing the serialized correlation data if extraction succeeds, or null otherwise.
     */
    private fun extractCorrelationJson(
        eventFilter: EventFilter,
        eventData: JsonElement
    ): String? = extractCorrelationValues(eventFilter, eventData)
        ?.let { Json.encodeToString(it.toSortedMap().toMap()) }

    /** Extracts correlation values from the event data using the filter's correlation definitions.*/
    private fun extractCorrelationValues(
        eventFilter: EventFilter,
        eventData: JsonElement
    ): Map<String, String>? {
        val correlate = eventFilter.correlate?.additionalProperties ?: return null
        if (correlate.isEmpty()) return null

        return try {
            val extractedValues = mutableMapOf<String, String>()

            for ((key, correlateValue) in correlate) {
                val fromExpr = correlateValue.from ?: continue

                // Evaluate the 'from' expression against event data
                val trimmedExpr = if (ExpressionUtils.isExpr(fromExpr)) {
                    ExpressionUtils.trimExpr(fromExpr)
                } else {
                    fromExpr
                }

                val value = when (val result = evaluateJqExpression(trimmedExpr, eventData)) {
                    is JsonPrimitive -> result.contentOrNull
                    else -> result.toString()
                }

                if (value != null) {
                    extractedValues[key] = value
                }
            }

            extractedValues.ifEmpty { null }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract correlation values from event" }
            null
        }
    }

    /**
     * Checks if a filter matches the given CloudEvent.
     */
    private fun filterMatches(
        filter: EventFilter,
        event: CloudEvent,
        eventDataProvider: () -> JsonElement
    ): Boolean {
        val eventProps = filter.with ?: return true

        // Literal-only fields: exact string match
        if (!matchesLiteralField(eventProps.type, event.type)) return false
        if (!matchesLiteralField(eventProps.id, event.id)) return false
        if (!matchesLiteralField(eventProps.subject, event.subject)) return false
        if (!matchesLiteralField(eventProps.datacontenttype, event.dataContentType)) return false

        // Expression-capable fields
        if (!matchesExprField(
                resolveSourceValue(eventProps.source),
                event.source?.toString()
            )
        ) return false

        if (!matchesExprField(
                resolveDataschemaValue(eventProps.dataschema),
                event.dataSchema?.toString()
            )
        ) return false

        if (!matchesExprField(
                resolveTimeValue(eventProps.time),
                event.time?.toString()
            )
        ) return false

        if (!matchesExprField(
                eventProps.data?.get()?.toString(),
                eventDataProvider()
            )
        ) return false

        return true
    }

    private fun resolveSourceValue(source: EventSource?): String? {
        if (source == null) return null
        return when (val value = source.get()) {
            is UriTemplate -> when (val uri = value.get()) {
                is URI -> uri.toString()
                is String -> uri
                else -> null
            }

            is String -> value
            else -> null
        }
    }

    private fun resolveDataschemaValue(dataschema: EventDataschema?): String? {
        if (dataschema == null) return null
        return when (val value = dataschema.get()) {
            is URI -> value.toString()
            is String -> value
            else -> null
        }
    }

    private fun resolveTimeValue(time: EventTime?): String? {
        if (time == null) return null
        return when (val value = time.get()) {
            is OffsetDateTime -> value.toString()
            is String -> value
            else -> null
        }
    }

    /**
     * Matches a literal-only field.
     */
    private fun matchesLiteralField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true
        return filterValue == eventValue
    }

    /**
     * Matches an expression-capable field.
     */
    private fun matchesExprField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true

        return if (ExpressionUtils.isExpr(filterValue)) {
            evaluateStringAsBoolean(filterValue, eventValue)
        } else {
            filterValue == eventValue
        }
    }

    private fun matchesExprField(filterValue: String?, eventValue: JsonElement): Boolean {
        if (filterValue == null) return true

        return if (ExpressionUtils.isExpr(filterValue)) {
            evaluateJsonElementAsBoolean(filterValue, eventValue)
        } else {
            // Compare literal JSON values
            try {
                Json.parseToJsonElement(filterValue)
            } catch (_: Exception) {
                null
            } == eventValue
        }
    }

    /**
     * Evaluates an expression against a string value and expects a boolean result.
     */
    private fun evaluateJsonElementAsBoolean(expression: String, value: JsonElement): Boolean {
        if (value == JsonNull) return false

        return try {
            val trimmedExpr = ExpressionUtils.trimExpr(expression)
            val result = evaluateJqExpression(trimmedExpr, value)
            (result as? JsonPrimitive)?.booleanOrNull == true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to evaluate expression: $expression against value: $value" }
            false
        }

    }

    private fun evaluateStringAsBoolean(expression: String, value: String?) =
        evaluateJsonElementAsBoolean(expression, value?.let { JsonPrimitive(it) } ?: JsonNull)

    /**
     * Evaluates a JQ expression against input data.
     */
    private fun evaluateJqExpression(expression: String, input: JsonElement): JsonElement {
        return with(LemlineJson) {
            val inputNode = input.toJsonNode()
            val scope = JsonObject(emptyMap()).toJsonNode() as ObjectNode
            JQExpression.eval(inputNode, expression, scope).toJsonElement()
        }
    }
}
