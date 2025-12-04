// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.definitions

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.common.values.name
import com.lemline.common.values.namespace
import com.lemline.common.values.version
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.expressions.JQExpression
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.OneEventConsumptionStrategy
import io.serverlessworkflow.api.types.Workflow
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Represents a listen task found in a workflow definition.
 */
@ExperimentalTime
data class ListenTaskInfo(
    val workflowNamespace: WorkflowNamespace,
    val workflowName: WorkflowName,
    val workflowVersion: WorkflowVersion,
    val nodePosition: NodePosition,
    val filters: List<EventFilter>
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
     * Finds all active listeners that match a CloudEvent, including correlation matching.
     *
     * This method:
     * 1. Finds listen tasks with filters that match the CloudEvent attributes
     * 2. For each matching filter, extracts correlation values from the event
     * 3. Queries active listeners using correlation values in the database query
     *
     * @param event The incoming CloudEvent to match against
     * @return List of active listeners interested in this event
     */
    suspend fun findMatchingListeners(event: CloudEvent): List<ListenerModel> {
        logger.debug { "Finding matching listeners for CloudEvent: type=${event.type}, source=${event.source}" }

        // Parse event data lazily (only if needed for filter evaluation)
        val eventData by lazy { parseEventData(event) }

        // Build unique query keys for matching listen tasks
        val queryKeys = mutableSetOf<ListenerQueryKey>()

        for (listenTask in getAllListenTasks()) {
            for (filter in listenTask.filters) {
                if (filterMatches(filter, event, eventData)) {
                    val correlationJson = extractCorrelationValues(filter, eventData)
                        ?.let { serializeCorrelationValues(it) }

                    queryKeys.add(
                        ListenerQueryKey(
                            namespace = listenTask.workflowNamespace,
                            name = listenTask.workflowName,
                            version = listenTask.workflowVersion,
                            position = listenTask.nodePosition,
                            correlationValuesJson = correlationJson
                        )
                    )
                }
            }
        }

        if (queryKeys.isEmpty()) {
            logger.trace { "No filters match the event" }
            return emptyList()
        }

        // Batch query all matching listeners
        val listeners = listenerRepository.findByKeys(queryKeys.toList())

        logger.debug { "Found ${listeners.size} matching listeners for event type=${event.type}" }
        return listeners
    }

    /**
     * Retrieves all listen tasks from all cached workflow definitions.
     */
    private fun getAllListenTasks(): List<ListenTaskInfo> {
        return DefinitionCache.getAllWorkflows().flatMap { workflow ->
            extractListenTasks(workflow)
        }
    }

    /**
     * Extracts listen tasks from a single workflow.
     */
    fun extractListenTasks(workflow: Workflow): List<ListenTaskInfo> {
        val listenTasks = mutableListOf<ListenTaskInfo>()
        val nodesMap = DefinitionCache.getNodesMap(workflow)

        for ((position, node) in nodesMap) {
            val listenTask = node.task as? ListenTask ?: continue
            val filters = getFiltersFromListenTask(listenTask)

            if (filters.isNotEmpty()) {
                listenTasks.add(
                    ListenTaskInfo(
                        workflowNamespace = workflow.namespace,
                        workflowName = workflow.name,
                        workflowVersion = workflow.version,
                        nodePosition = position,
                        filters = filters
                    )
                )
            }
        }

        return listenTasks
    }

    /**
     * Gets the list of EventFilters from a ListenTask.
     */
    private fun getFiltersFromListenTask(listenTask: ListenTask): List<EventFilter> {
        val listenTo = listenTask.listen?.to?.get() ?: return emptyList()

        return when (listenTo) {
            is OneEventConsumptionStrategy -> listOfNotNull(listenTo.one)
            is AnyEventConsumptionStrategy -> listenTo.any ?: emptyList()
            is AllEventConsumptionStrategy -> listenTo.all ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * Serializes correlation values to JSON with sorted keys for consistent database comparison.
     */
    private fun serializeCorrelationValues(values: Map<String, String>): String {
        val sortedEntries = values.entries.sortedBy { it.key }
        return buildJsonObject {
            for ((key, value) in sortedEntries) {
                put(key, value)
            }
        }.let { Json.encodeToString(it) }
    }

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
     * Matches the data filter against the event's data payload.
     */
    private fun matchesDataFilter(filterValue: String?, eventData: JsonElement): Boolean {
        if (filterValue == null) return true

        return try {
            if (ExpressionUtils.isExpr(filterValue)) {
                val expression = ExpressionUtils.trimExpr(filterValue)
                val result = evaluateJqExpression(expression, eventData)
                (result as? JsonPrimitive)?.booleanOrNull == true
            } else {
                // Literal match: parse as JSON and compare
                try {
                    val filterData = Json.parseToJsonElement(filterValue)
                    filterData == eventData
                } catch (e: Exception) {
                    filterValue == eventData.toString()
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to evaluate data filter expression: $filterValue" }
            false
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
