// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.definitions.CachedUntilCondition
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.definitions.ListenerMatch
import com.lemline.runner.definitions.TerminationMatch
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.repositories.ListenerEventRepository
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.EventFilter
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles incoming CloudEvents by matching them against active listeners
 * and marking matched listeners ready for completion.
 *
 * ## Processing Flow
 *
 * 1. **Service Query**: Use DefinitionListenService to find matching listeners with context
 * 2. **Batch Grouping**: Group matches by strategy and readAs mode
 * 3. **Strategy-Specific Processing**:
 *    - ONE/ANY: Single batch UPDATE on listeners table
 *    - ALL: Batch INSERT into events table + COUNT check + batch UPDATE
 * 4. **Completion**: ListenerCompletionOutbox sends resume commands
 *
 * ## Batch Optimization
 *
 * Instead of processing listeners one-by-one, this handler:
 * - Groups ONE/ANY listeners for single batch UPDATE
 * - Groups ALL listeners by (filterIndex, totalFilters, readAs) for batch INSERT
 * - Extracts event data once per readAs mode (not per listener)
 *
 * For 1000 listeners, this reduces database calls from 1000+ to just a few.
 *
 * ## Race Condition Prevention
 *
 * - ONE/ANY: Atomic UPDATE with WHERE guards prevents double-completion
 * - ALL: Idempotent INSERT (ON CONFLICT DO NOTHING) + atomic UPDATE prevents races
 *
 * @see ListenerCompletionOutbox for completion processing
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class CloudEventHandler(
    private val definitionListenService: DefinitionListenService,
    private val listenerRepository: ListenerRepository,
) {
    private val logger = logger()

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    /**
     * Processes an incoming CloudEvent by matching it against active listeners.
     *
     * Uses batch operations to efficiently handle thousands of listeners:
     * - ONE/ANY (without until) strategy: Single batch UPDATE on listeners table
     * - ALL strategy: Batch INSERT into events table + COUNT + batch UPDATE
     * - ANY + until strategy: Batch INSERT + condition evaluation + batch UPDATE
     * - Termination events: Complete ANY+until(event) listeners with accumulated events
     *
     * @param event The incoming CloudEvent
     * @return Number of listeners that were affected (completed or updated)
     */
    suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        var affectedCount = 0

        // Find all matching listeners with full context for batch processing
        val matches = definitionListenService.findMatchingListeners(event)

        // Also check for termination matches (for ANY + until(event) strategy)
        val terminationMatches = definitionListenService.findTerminationListeners(event)

        if (matches.isEmpty() && terminationMatches.isEmpty()) {
            logger.trace { "No matching listeners for event type: $eventType" }
            return 0
        }

        // Process regular matches
        if (matches.isNotEmpty()) {
            logger.debug { "Found ${matches.size} listener matches for event type: $eventType" }

            // Extract event data once per readAs mode (avoid repeated parsing)
            val eventDataByReadAs = matches.map { it.readAs }.toSet().associateWith { readAs ->
                extractEventContent(event, readAs)
            }

            // Separate matches by strategy
            // ONE and ANY without until: immediate completion
            val oneAnyMatches = matches.filter {
                it.strategy == ListenStrategy.ONE || (it.strategy == ListenStrategy.ANY && it.until == null)
            }

            // Process ONE/ANY (without until) - single batch UPDATE
            if (oneAnyMatches.isNotEmpty()) {
                affectedCount += processOneAny(oneAnyMatches, eventDataByReadAs)
            }

            // ANY with until: accumulation mode
            val anyWithUntilMatches = matches.filter {
                it.strategy == ListenStrategy.ANY && it.until != null
            }
            // Process ANY + until - accumulate events until condition met
            if (anyWithUntilMatches.isNotEmpty()) {
                affectedCount += processAnyWithUntil(anyWithUntilMatches, eventDataByReadAs, event)
            }

            // ALL: multi-filter completion
            val allMatches = matches.filter { it.strategy == ListenStrategy.ALL }
            
            // Process ALL - batch INSERT + COUNT + batch UPDATE
            if (allMatches.isNotEmpty()) {
                affectedCount += processAll(allMatches, eventDataByReadAs)
            }
        }

        // Process termination matches (for ANY + until(event) strategy)
        if (terminationMatches.isNotEmpty()) {
            logger.debug { "Found ${terminationMatches.size} termination matches for event type: $eventType" }
            affectedCount += processTerminationEvents(terminationMatches)
        }

        logger.debug { "CloudEvent processing complete: $affectedCount listeners affected" }
        return affectedCount
    }

    /**
     * Processes termination events for ANY + until(event) strategy.
     *
     * Completes listeners with all their accumulated events (excluding the termination event).
     *
     * @return Number of listeners that were completed
     */
    private suspend fun processTerminationEvents(matches: List<TerminationMatch>): Int {
        val listenerIds = matches.map { it.listener.id }

        // Fetch all accumulated events for these listeners
        val eventsByListener = listenerEventRepository.batchFindByListenerIds(listenerIds)

        val listenerEvents = matches.mapNotNull { match ->
            val listenerId = match.listener.id
            val events = eventsByListener[listenerId] ?: emptyList()
            // Return accumulated events (termination event is NOT included per spec)
            val eventsArray = JsonArray(events.map { Json.parseToJsonElement(it.event) })
            listenerId to Json.encodeToString(eventsArray)
        }.toMap()

        val completed = listenerRepository.batchMarkReadyForCompletionFromEvents(listenerEvents)

        if (completed > 0) {
            logger.info { "Batch marked $completed listeners ready for completion (termination event received)" }
        }

        return completed
    }

    /**
     * Processes ONE/ANY strategy matches.
     *
     * Groups listeners by readAs mode and executes one batch UPDATE per group.
     * All listeners in a group get the same event data stored directly in the listeners table.
     *
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processOneAny(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
    ): Int {
        // Group by readAs mode (different event data format)
        val byReadAs = matches.groupBy { it.readAs }

        var totalAffected = 0

        for ((readAs, matchGroup) in byReadAs) {
            val eventData = eventDataByReadAs[readAs] ?: continue
            // Store single event as JSON (will be wrapped in array at completion time)
            val eventJson = Json.encodeToString(eventData)

            // Get unique listener IDs (same listener might match multiple filters, but we only need one)
            val listenerIds = matchGroup.map { it.listener.id }.distinct()

            val affected = listenerRepository.batchMarkReadyForCompletion(
                ids = listenerIds,
                event = eventJson
            )

            if (affected > 0) {
                logger.info { "Batch marked $affected ONE/ANY listeners ready for completion (readAs=$readAs)" }
            }
            totalAffected += affected
        }

        return totalAffected
    }

    /**
     * Processes ANY + until strategy matches (accumulation mode).
     *
     * For ANY + until:
     * 1. Batch INSERT events into lemline_listener_events table
     * 2. Check termination condition:
     *    - Expression: evaluate against accumulated events array
     *    - Event: check if this event matches termination filter
     * 3. Batch UPDATE listeners where condition is met
     *
     * Unlike ALL strategy, events don't have fixed filter indices - they're appended
     * in arrival order using auto-increment indices.
     *
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processAnyWithUntil(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
        event: CloudEvent
    ): Int {
        // Group by until condition type and readAs
        val expressionMatches = matches.filter { it.until is CachedUntilCondition.Expression }
        val eventTerminationMatches = matches.filter { it.until is CachedUntilCondition.Event }

        var totalAffected = 0

        // Process expression-based until conditions
        if (expressionMatches.isNotEmpty()) {
            totalAffected += processAnyWithUntilExpression(expressionMatches, eventDataByReadAs)
        }

        // Process event-based until conditions (termination events)
        if (eventTerminationMatches.isNotEmpty()) {
            totalAffected += processAnyWithUntilEvent(eventTerminationMatches, eventDataByReadAs, event)
        }

        return totalAffected
    }

    /**
     * Processes ANY + until with expression condition.
     *
     * Events are accumulated and the expression is evaluated after each new event.
     * When the expression returns true, the listener is marked for completion.
     */
    private suspend fun processAnyWithUntilExpression(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
    ): Int {
        // Build event models for batch insert
        val eventModels = mutableListOf<ListenerEventModel>()
        val listenerUntilExpr = mutableMapOf<IDV7, String>()

        for (match in matches) {
            val eventData = eventDataByReadAs[match.readAs] ?: continue
            val eventJson = Json.encodeToString(eventData)
            val listenerId = match.listener.id
            val untilExpr = (match.until as? CachedUntilCondition.Expression)?.expression ?: continue

            // For ANY+until, use auto-increment index (not filter-based like ALL)
            // The eventId needs to be unique per event, not per filter
            // We use a random suffix since events can arrive at any time
            val eventId = IDV7.random()

            eventModels.add(
                ListenerEventModel(
                    id = eventId,
                    listenerId = listenerId,
                    filterIndex = null,  // null for accumulation mode (allows multiple events per listener)
                    event = eventJson
                )
            )

            listenerUntilExpr[listenerId] = untilExpr
        }

        if (eventModels.isEmpty()) return 0

        // Step 1: Batch INSERT events
        val listenerIds = listenerUntilExpr.keys.toList()
        val inserted = listenerEventRepository.insert(eventModels)
        logger.debug { "Inserted $inserted events for ANY+until listeners" }

        // Step 2: Fetch all accumulated events for these listeners
        val eventsByListener = listenerEventRepository.batchFindByListenerIds(listenerIds)

        // Step 3: Evaluate until expression for each listener
        val listenerEvents = mutableMapOf<IDV7, String>()

        for ((listenerId, events) in eventsByListener) {
            val untilExpr = listenerUntilExpr[listenerId] ?: continue

            // Build JSON array of accumulated events
            val eventsArray = JsonArray(events.map { Json.parseToJsonElement(it.event) })

            // Evaluate the until expression against the events array
            val shouldComplete = try {
                evaluateUntilExpression(untilExpr, eventsArray)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate until expression for listener $listenerId: $untilExpr" }
                false
            }

            if (shouldComplete) {
                listenerEvents[listenerId] = Json.encodeToString(eventsArray)
                logger.debug { "ANY+until expression evaluated to true for listener $listenerId" }
            }
        }

        if (listenerEvents.isEmpty()) {
            logger.debug { "No ANY+until listeners completed yet (expression not satisfied)" }
            return 0
        }

        // Step 4: Batch UPDATE completed listeners
        val completed = listenerRepository.batchMarkReadyForCompletionFromEvents(listenerEvents)

        if (completed > 0) {
            logger.info { "Batch marked $completed ANY+until listeners ready for completion (expression satisfied)" }
        }

        return completed
    }

    /**
     * Processes ANY + until with event termination condition.
     *
     * This method only handles accumulation - the event matched a main filter,
     * so it should be added to the listener's accumulated events.
     * Termination events are handled separately by processTerminationEvents().
     */
    private suspend fun processAnyWithUntilEvent(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
        @Suppress("UNUSED_PARAMETER") event: CloudEvent
    ): Int {
        val eventModels = mutableListOf<ListenerEventModel>()

        for (match in matches) {
            val eventDataJson = eventDataByReadAs[match.readAs] ?: continue
            val eventJson = Json.encodeToString(eventDataJson)
            val listenerId = match.listener.id
            val eventId = IDV7.random()

            eventModels.add(
                ListenerEventModel(
                    id = eventId,
                    listenerId = listenerId,
                    filterIndex = null,  // null for accumulation mode (allows multiple events per listener)
                    event = eventJson
                )
            )
        }

        if (eventModels.isNotEmpty()) {
            val inserted = listenerEventRepository.insert(eventModels)
            logger.debug { "Inserted $inserted events for ANY+until(event) listeners (accumulating)" }
        }

        // Accumulation doesn't complete listeners - just stores events
        // Completion happens when a termination event arrives (via processTerminationEvents)
        return 0
    }

    /**
     * Evaluates an until expression against accumulated events.
     * The expression should return a boolean.
     */
    private fun evaluateUntilExpression(expression: String, events: JsonArray): Boolean {
        return try {
            with(LemlineJson) {
                val inputNode = events.toJsonNode()
                val scope = JsonObject(emptyMap()).toJsonNode() as ObjectNode
                val result = JQExpression.eval(inputNode, expression, scope).toJsonElement()
                (result as? JsonPrimitive)?.booleanOrNull == true
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to evaluate until expression: $expression" }
            false
        }
    }

    /**
     * Checks if the CloudEvent matches the termination filter.
     */
    private fun filterMatches(
        filter: EventFilter,
        event: CloudEvent,
        eventData: JsonElement
    ): Boolean {
        val eventProps = filter.with ?: return true

        // Literal-only fields: exact string match
        if (!matchesLiteralField(eventProps.type, event.type)) return false
        if (!matchesLiteralField(eventProps.id, event.id)) return false
        if (!matchesLiteralField(eventProps.subject, event.subject)) return false
        if (!matchesLiteralField(eventProps.datacontenttype, event.dataContentType)) return false

        // Expression-capable fields
        if (!matchesExprField(eventProps.source?.get()?.toString(), event.source?.toString())) return false
        if (!matchesExprField(eventProps.dataschema?.get()?.toString(), event.dataSchema?.toString())) return false
        if (!matchesExprField(eventProps.time?.get()?.toString(), event.time?.toString())) return false
        if (!matchesExprField(eventProps.data?.get()?.toString(), eventData)) return false

        return true
    }

    private fun matchesLiteralField(filterValue: String?, eventValue: String?): Boolean {
        if (filterValue == null) return true
        return filterValue == eventValue
    }

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
            try {
                Json.parseToJsonElement(filterValue)
            } catch (_: Exception) {
                null
            } == eventValue
        }
    }

    private fun evaluateStringAsBoolean(expression: String, value: String?) =
        evaluateJsonElementAsBoolean(expression, value?.let { JsonPrimitive(it) } ?: JsonNull)

    private fun evaluateJsonElementAsBoolean(expression: String, value: JsonElement): Boolean {
        if (value == JsonNull) return false

        return try {
            val trimmedExpr = ExpressionUtils.trimExpr(expression)
            with(LemlineJson) {
                val inputNode = value.toJsonNode()
                val scope = JsonObject(emptyMap()).toJsonNode() as ObjectNode
                val result = JQExpression.eval(inputNode, trimmedExpr, scope).toJsonElement()
                (result as? JsonPrimitive)?.booleanOrNull == true
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to evaluate expression: $expression against value: $value" }
            false
        }
    }

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

    /**
     * Processes ALL strategy matches.
     *
     * For ALL strategy:
     * 1. Batch INSERT events into lemline_listener_events table (idempotent)
     * 2. Batch COUNT events per listener to check completion
     * 3. Batch UPDATE listeners that are now complete (COUNT = totalFilters)
     *
     * This approach:
     * - Uses insert-only pattern (no read-modify-write race conditions)
     * - Idempotent inserts via ON CONFLICT DO NOTHING
     * - Minimizes database requests (3 requests total regardless of listener count)
     *
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processAll(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
    ): Int {
        // Group by (filterIndex, totalFilters, readAs) for batch insert
        data class AllGroupKey(val filterIndex: Int, val totalFilters: Int, val readAs: ListenAndReadAs)

        val byGroup = matches.groupBy { AllGroupKey(it.filterIndex, it.totalFilters, it.readAs) }

        // Build all event models for batch insert
        val eventModels = mutableListOf<ListenerEventModel>()
        val listenerTotalFilters = mutableMapOf<IDV7, Int>()

        for ((groupKey, matchGroup) in byGroup) {
            val eventData = eventDataByReadAs[groupKey.readAs] ?: continue
            val eventJson = Json.encodeToString(eventData)

            for (match in matchGroup) {
                val listenerId = match.listener.id

                // Derive idempotent ID for ALL: listener_id + filter_index
                val eventId = listenerId.derive("-filter-${groupKey.filterIndex}")

                eventModels.add(
                    ListenerEventModel(
                        id = eventId,
                        listenerId = listenerId,
                        filterIndex = groupKey.filterIndex,  // Explicit filter index for ALL
                        event = eventJson
                    )
                )

                // Track totalFilters for completion check
                listenerTotalFilters[listenerId] = groupKey.totalFilters
            }
        }

        if (eventModels.isEmpty()) return 0

        // Step 1: Batch INSERT events (idempotent - duplicates ignored)
        val listenerIds = listenerTotalFilters.keys.toList()
        val inserted = listenerEventRepository.insert(eventModels)
        logger.debug { "Inserted $inserted events for ALL strategy listeners" }

        // Step 2: Fetch all events and filter to completed listeners in code
        // This combines COUNT + FETCH into a single query - count is derived from list.size()
        val eventsByListener = listenerEventRepository.batchFindByListenerIds(listenerIds)

        // Step 3: Filter to completed listeners and aggregate events into JSON arrays
        val listenerEvents = eventsByListener
            .mapNotNull { (listenerId, events) ->
                val totalFilters = listenerTotalFilters[listenerId] ?: return@mapNotNull null
                if (events.size < totalFilters) return@mapNotNull null
                // Take only first totalFilters events (safety: handle race condition with extra events)
                // Events are already ordered by filter_index from the query
                val jsonArray = JsonArray(events.take(totalFilters).map { Json.parseToJsonElement(it.event) })
                listenerId to Json.encodeToString(jsonArray)
            }
            .toMap()

        if (listenerEvents.isEmpty()) {
            logger.debug { "No ALL listeners completed yet (filters still pending)" }
            return 0
        }

        // Step 4: Batch UPDATE completed listeners with aggregated events
        val completed = listenerRepository.batchMarkReadyForCompletionFromEvents(listenerEvents)

        if (completed > 0) {
            logger.info { "Batch marked $completed ALL listeners ready for completion (all filters matched)" }
        }

        return completed
    }

    /**
     * Extracts the event content based on the readAs mode.
     *
     * @param event The CloudEvent
     * @param readAs The extraction mode (DATA, ENVELOPE, RAW)
     * @return The extracted content as JsonElement
     */
    private fun extractEventContent(event: CloudEvent, readAs: ListenAndReadAs): JsonElement {
        return when (readAs) {
            ListenAndReadAs.DATA -> {
                // Extract just the data payload
                event.data?.let { data ->
                    try {
                        val bytes = data.toBytes()
                        if (bytes.isNotEmpty()) {
                            Json.parseToJsonElement(String(bytes))
                        } else {
                            JsonNull
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse CloudEvent data as JSON, using null" }
                        JsonNull
                    }
                } ?: JsonNull
            }

            ListenAndReadAs.ENVELOPE -> {
                // Return the full event structure
                buildJsonObject {
                    // Explicitly set specversion to ensure it's present and first
                    put("specversion", event.specVersion.toString())

                    // Dynamically add all other context attributes (standard + extensions)
                    event.attributeNames.forEach { name ->
                        // Skip specversion if it appears in attributes to avoid redundancy
                        if (name == "specversion") return@forEach

                        when (val value = event.getAttribute(name)) {
                            is Number -> put(name, value)
                            is Boolean -> put(name, value)
                            // Handle URIs, Time, Strings, etc. via toString()
                            else -> value?.let { put(name, it.toString()) }
                        }
                    }

                    // Add data
                    event.data?.let { data ->
                        val parsed = runCatching {
                            Json.parseToJsonElement(String(data.toBytes()))
                        }.getOrElse { JsonNull }
                        put("data", parsed)
                    }
                }
            }

            ListenAndReadAs.RAW -> {
                // Return raw bytes as base64 or string
                event.data?.let { data ->
                    JsonPrimitive(String(data.toBytes()))
                } ?: JsonNull
            }
        }
    }
}
