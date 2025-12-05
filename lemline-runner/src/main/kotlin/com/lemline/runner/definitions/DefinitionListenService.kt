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
     * Key for grouping filter matches before querying listeners.
     */
    private data class FilterMatchKey(
        val workflowInfo: WorkflowInfo,
        val nodePosition: NodePosition,
        val correlationValuesJson: String?,
        val filterIndex: Int,
        val totalFilters: Int,
        val strategy: ListenStrategy,
        val readAs: ListenTaskConfiguration.ListenAndReadAs,
        val until: CachedUntilCondition?
    )

    /**
     * Finds all active listeners with full context needed for batch processing.
     *
     * This method:
     * 1. Finds listen tasks with filters that match the CloudEvent attributes
     * 2. For each matching filter, extracts correlation values and filter index
     * 3. Queries active listeners and enriches them with strategy context
     *
     * The returned ListenerMatch includes all information needed for batch updates:
     * - strategy (ONE/ANY/ALL)
     * - readAs (DATA/ENVELOPE/RAW)
     * - filterIndex (which filter matched - important for ALL strategy)
     * - totalFilters (total number of filters - important for ALL strategy)
     *
     * @param event The incoming CloudEvent to match against
     * @return List of listener matches with full context
     */
    suspend fun findMatchingListeners(event: CloudEvent): List<ListenerMatch> {
        logger.debug { "Finding matching listeners for CloudEvent: type=${event.type}, source=${event.source}" }

        // Parse event data lazily (only if needed for filter evaluation)
        val eventData by lazy { parseEventData(event) }

        // Build filter match keys - tracks which filter index matched for each listen task
        // Uses cached listen tasks from DefinitionCache (computed once per workflow add)
        val filterMatchKeys = mutableListOf<FilterMatchKey>()

        filterMatchKeys.addAll(
            DefinitionCache.getAllListenTasks().flatMap { task ->
                task.filters.mapIndexedNotNull { index, filter ->
                    if (!filterMatches(filter, event, eventData)) return@mapIndexedNotNull null

                    FilterMatchKey(
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
        )

        if (filterMatchKeys.isEmpty()) {
            logger.trace { "No filters match the event" }
            return emptyList()
        }

        // Build unique query keys (same listener might match multiple filters for ALL strategy)
        val queryKeys = filterMatchKeys.map { key: FilterMatchKey ->
            ListenerQueryKey(
                workflowInfo = key.workflowInfo,
                position = key.nodePosition,
                correlationValuesJson = key.correlationValuesJson
            )
        }.distinct()

        // Batch query all matching listeners
        val listeners = listenerRepository.findByKeys(queryKeys)

        if (listeners.isEmpty()) {
            logger.trace { "No active listeners match the filters" }
            return emptyList()
        }

        // Create a lookup map for filter match context
        // Key: (namespace, name, version, position) -> list of FilterMatchKey
        val filterMatchByPosition = filterMatchKeys.groupBy { key ->
            Pair(key.workflowInfo, key.nodePosition)
        }

        // Build ListenerMatch results by joining listeners with their filter context
        val results = mutableListOf<ListenerMatch>()

        for (listener in listeners) {
            val positionKey = Pair(
                WorkflowInfo(listener.workflowNamespace, listener.workflowName, listener.workflowVersion),
                listener.nodePosition
            )

            val matchingFilters = filterMatchByPosition[positionKey] ?: continue

            // For ONE/ANY (without until): only need one match (first filter)
            // For ALL and ANY+until: need to emit one ListenerMatch per matched filter
            for (filterMatch in matchingFilters) {
                results.add(
                    ListenerMatch(
                        listener = listener,
                        strategy = filterMatch.strategy,
                        readAs = filterMatch.readAs,
                        filterIndex = filterMatch.filterIndex,
                        totalFilters = filterMatch.totalFilters,
                        until = filterMatch.until
                    )
                )

                // For ONE/ANY without until, only need one match per listener
                // For ALL and ANY+until, need all matches for event tracking
                val needsAllMatches = filterMatch.strategy == ListenStrategy.ALL ||
                    (filterMatch.strategy == ListenStrategy.ANY && filterMatch.until != null)
                if (!needsAllMatches) break
            }
        }

        logger.debug { "Found ${results.size} listener matches for event type=${event.type}" }
        return results
    }

    /**
     * Finds active listeners that should be terminated by this CloudEvent.
     *
     * This is specifically for ANY + until(event) strategy where a termination event
     * (different from the main event filters) triggers completion of the listener.
     *
     * @param event The incoming CloudEvent to match against termination filters
     * @return List of listeners that should be terminated with their readAs config
     */
    suspend fun findTerminationListeners(event: CloudEvent): List<TerminationMatch> {
        logger.debug { "Finding termination listeners for CloudEvent: type=${event.type}, source=${event.source}" }

        // Parse event data lazily
        val eventData by lazy { parseEventData(event) }

        // Find listen tasks with termination filters that match this event
        data class TerminationKey(
            val workflowInfo: WorkflowInfo,
            val nodePosition: NodePosition,
            val readAs: ListenTaskConfiguration.ListenAndReadAs
        )

        val terminationKeys = mutableListOf<TerminationKey>()

        for (task in DefinitionCache.getAllListenTasks()) {
            // Only check tasks with termination filters
            val terminationFilter = task.terminationFilter ?: continue

            // Check if this event matches the termination filter
            if (!filterMatches(terminationFilter, event, eventData)) continue

            terminationKeys.add(
                TerminationKey(
                    workflowInfo = task.workflowInfo,
                    nodePosition = task.nodePosition,
                    readAs = task.readAs
                )
            )
        }

        if (terminationKeys.isEmpty()) {
            logger.trace { "No termination filters match the event" }
            return emptyList()
        }

        // Build query keys to find active listeners
        val queryKeys = terminationKeys.map { key ->
            ListenerQueryKey(
                workflowInfo = key.workflowInfo,
                position = key.nodePosition,
                correlationValuesJson = null  // Termination doesn't use correlation
            )
        }

        // Query active listeners
        val listeners = listenerRepository.findByKeys(queryKeys)

        if (listeners.isEmpty()) {
            logger.trace { "No active listeners match the termination filters" }
            return emptyList()
        }

        // Create a lookup map for readAs config
        val readAsByPosition = terminationKeys.associateBy(
            { Pair(it.workflowInfo, it.nodePosition) },
            { it.readAs }
        )

        // Build termination matches
        val results = listeners.mapNotNull { listener ->
            val positionKey = Pair(
                WorkflowInfo(listener.workflowNamespace, listener.workflowName, listener.workflowVersion),
                listener.nodePosition
            )
            val readAs = readAsByPosition[positionKey] ?: return@mapNotNull null
            TerminationMatch(listener = listener, readAs = readAs)
        }

        logger.debug { "Found ${results.size} termination matches for event type=${event.type}" }
        return results
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
