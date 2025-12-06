// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.CachedUntilCondition
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.ListenTaskConfiguration
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
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
data class DefinitionMatch(
    val workflowInfo: WorkflowInfo,
    val nodePosition: NodePosition,
    /** Correlation values extracted from the event using correlate.from expressions */
    val correlationValuesJson: String?,
    /** Filter index that matched - relevant for ALL and ANY+until strategies */
    val filterIndex: Int,
    /** Total number of filters - relevant for ALL and ANY+until strategies */
    val totalFilters: Int,
    val strategy: ListenStrategy,
    val readAs: ListenTaskConfiguration.ListenAndReadAs,
    /** Until condition for ANY+until accumulation mode (null for ONE, ANY without until, ALL) */
    val until: CachedUntilCondition? = null
) {
    /** Converts this definition match to a query key for listener lookup. */
    fun toQueryKey() = ListenerQueryKey(
        workflowInfo = workflowInfo,
        position = nodePosition,
        correlationValuesJson = correlationValuesJson
    )
}

/**
 * Represents a workflow definition whose termination filter matches an event.
 * Used for ANY + until(event) strategy where a specific event type triggers completion.
 */
@ExperimentalTime
@ExperimentalSerializationApi
data class TerminationDefinitionMatch(
    val workflowInfo: WorkflowInfo,
    val nodePosition: NodePosition,
    val readAs: ListenTaskConfiguration.ListenAndReadAs
) {
    /** Converts this termination match to a query key (without correlation - termination doesn't use it). */
    fun toQueryKey() = ListenerQueryKey(
        workflowInfo = workflowInfo,
        position = nodePosition,
        correlationValuesJson = null
    )
}

/**
 * Represents a matched listener with context needed for batch processing.
 */
@ExperimentalTime
@ExperimentalSerializationApi
data class ListenerMatch(
    val listener: ListenerModel,
    val strategy: ListenStrategy,
    val readAs: ListenTaskConfiguration.ListenAndReadAs,
    /** Filter index that matched - relevant for ALL and ANY+until strategies */
    val filterIndex: Int,
    /** Total number of filters - relevant for ALL and ANY+until strategies */
    val totalFilters: Int,
    /** Until condition for ANY+until accumulation mode (null for ONE, ANY without until, ALL) */
    val until: CachedUntilCondition? = null
)

/**
 * Represents a listener that should be terminated by a termination event.
 * Used for ANY + until(event) strategy where a specific event type triggers completion.
 */
@ExperimentalTime
@ExperimentalSerializationApi
data class TerminationMatch(
    val listener: ListenerModel,
    val readAs: ListenTaskConfiguration.ListenAndReadAs
)

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
 * @see DefinitionCache for workflow definition caching
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
     * Finds workflow definitions whose listen task filters match the CloudEvent.
     *
     * This method only queries the in-memory DefinitionCache, no database access.
     * For each matching filter, it extracts correlation values using the filter's
     * `correlate.from` expressions.
     *
     * @param event The incoming CloudEvent to match against
     * @return List of definition matches with correlation values
     */
    fun findMatchingDefinitions(event: CloudEvent): List<DefinitionMatch> {
        logger.debug { "Finding matching definitions for CloudEvent: type=${event.type}, source=${event.source}" }

        // Parse event data lazily (only if needed for filter evaluation)
        val eventData by lazy { parseEventData(event) }

        val matches = DefinitionCache.getAllListenTasks().flatMap { task ->
            task.filters.mapIndexedNotNull { index, filter ->
                if (!filterMatches(filter, event, eventData)) return@mapIndexedNotNull null

                DefinitionMatch(
                    workflowInfo = task.workflowInfo,
                    nodePosition = task.nodePosition,
                    correlationValuesJson = extractCorrelationValues(filter, eventData)
                        ?.let(::serializeCorrelationValues),
                    filterIndex = index,
                    totalFilters = task.filters.size,
                    strategy = task.strategy,
                    readAs = task.readAs,
                    until = task.until
                )
            }
        }

        logger.debug { "Found ${matches.size} definition matches for event type=${event.type}" }
        return matches
    }

    /**
     * Finds workflow definitions whose termination filter matches the CloudEvent.
     *
     * This is specifically for ANY + until(event) strategy where a termination event
     * (different from the main event filters) triggers completion of the listener.
     *
     * This method only queries the in-memory DefinitionCache, no database access.
     *
     * @param event The incoming CloudEvent to match against termination filters
     * @return List of termination definition matches
     */
    fun findDefinitionsUntilEvent(event: CloudEvent): List<TerminationDefinitionMatch> {
        logger.debug { "Finding termination definitions for CloudEvent: type=${event.type}, source=${event.source}" }

        // Parse event data lazily
        val eventData by lazy { parseEventData(event) }

        val matches = DefinitionCache.getAllListenTasks().mapNotNull { task ->
            // Only check tasks with termination filters
            val terminationFilter = task.untilEventFilter ?: return@mapNotNull null

            // Check if this event matches the termination filter
            if (!filterMatches(terminationFilter, event, eventData)) return@mapNotNull null

            TerminationDefinitionMatch(
                workflowInfo = task.workflowInfo,
                nodePosition = task.nodePosition,
                readAs = task.readAs
            )
        }

        logger.debug { "Found ${matches.size} termination definition matches for event type=${event.type}" }
        return matches
    }

    /**
     * Serializes correlation values to JSON with sorted keys for consistent database comparison.
     */
    private fun serializeCorrelationValues(values: Map<String, String>) =
        Json.encodeToString(values.toSortedMap())

    /**
     * Extracts correlation values from the event data using the filter's correlation definitions.
     */
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
        eventData: JsonElement
    ): Boolean {
        val eventProps = filter.with ?: return true // No filter criteria = match all

        // Literal-only fields: exact string match
        if (!matchesLiteralField(eventProps.type, event.type)) return false
        if (!matchesLiteralField(eventProps.id, event.id)) return false
        if (!matchesLiteralField(eventProps.subject, event.subject)) return false
        if (!matchesLiteralField(eventProps.datacontenttype, event.dataContentType)) return false

        // Expression-capable fields
        if (!matchesExprField(
                eventProps.source?.get()?.toString(),
                event.source?.toString()
            )
        ) return false
        if (!matchesExprField(
                eventProps.dataschema?.get()?.toString(),
                event.dataSchema?.toString()
            )
        ) return false
        if (!matchesExprField(
                eventProps.time?.get()?.toString(),
                event.time?.toString()
            )
        ) return false

        if (!matchesExprField(
                eventProps.data?.get()?.toString(),
                eventData
            )
        ) return false

        return true
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

    /**
     * Parses the CloudEvent data payload to JsonElement.
     */
    private fun parseEventData(event: CloudEvent): JsonElement {
        val data = event.data ?: return JsonNull
        return try {
            val bytes = data.toBytes()
            if (bytes.isEmpty()) {
                JsonNull
            } else {
                Json.parseToJsonElement(String(bytes))
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse CloudEvent data as JSON" }
            JsonNull
        }
    }
}
