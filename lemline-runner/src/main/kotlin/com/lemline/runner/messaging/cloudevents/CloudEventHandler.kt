// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.definitions.CachedUntilCondition
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.definitions.DefinitionMatch
import com.lemline.runner.definitions.ListenerMatch
import com.lemline.runner.definitions.TerminationDefinitionMatch
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.repositories.ListenerEventRepository
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
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
     * ## Processing Steps
     *
     * 1. **Definition Matching**: Query workflow definitions (in-memory) to find listen tasks
     *    whose filters match this event. Extract correlation values using `correlate.from`.
     *
     * 2. **Listener Query**: Query the database for active listeners that match the definitions.
     *
     * 3. **Strategy-Specific Processing**: Process matches independently by strategy:
     *    - ONE: Complete listener on first matching event
     *    - ANY: Complete listener on first matching event (same as ONE for single filter)
     *    - ANY + until: Accumulate events until termination condition is met
     *    - ALL: Accumulate events until all filters have matched
     *
     * Uses batch operations to efficiently handle thousands of listeners.
     *
     * @param event The incoming CloudEvent
     * @return Number of listeners that were affected (completed or updated)
     */
    suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 1: Match event against workflow definitions (in-memory, no database)
        //
        // For each workflow definition with a listen task:
        //   - Check if the event matches any of the task's filters
        //   - Extract correlation values using filter's correlate.from expressions
        // ═══════════════════════════════════════════════════════════════════════════════

        val definitionMatches = definitionListenService.findMatchingDefinitions(event)
        val definitionsUntilEvent = definitionListenService.findDefinitionsUntilEvent(event)

        if (definitionMatches.isEmpty() && definitionsUntilEvent.isEmpty()) {
            logger.trace { "No matching definitions for event type: $eventType" }
            return 0
        }

        logger.debug {
            "Definition matching complete: ${definitionMatches.size} definition matches, " +
                "${definitionsUntilEvent.size} termination definitions for event type: $eventType"
        }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 2: Process each strategy independently
        //
        // ONE/ANY (without until): Direct UPDATE without SELECT (avoids loading millions of rows)
        // ANY+until, ALL: Still requires SELECT for event accumulation logic
        // ═══════════════════════════════════════════════════════════════════════════════

        var affectedCount = 0

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ONE - Direct UPDATE (no SELECT needed)
        // Complete listener immediately on first matching event
        // ─────────────────────────────────────────────────────────────────────────────
        val oneDefinitions = definitionMatches.filter { it.strategy == ListenStrategy.ONE }
        if (oneDefinitions.isNotEmpty()) {
            affectedCount += processOneAnyDirect(oneDefinitions, event)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ANY (without until) - Direct UPDATE (no SELECT needed)
        // Complete listener immediately on first matching event from any filter
        // ─────────────────────────────────────────────────────────────────────────────
        val anyDefinitions = definitionMatches.filter { it.strategy == ListenStrategy.ANY && it.until == null }
        if (anyDefinitions.isNotEmpty()) {
            affectedCount += processOneAnyDirect(anyDefinitions, event)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ANY + until(expression) - Direct INSERT + Streaming
        // Bulk insert events without loading listeners, then stream for expression eval
        // ─────────────────────────────────────────────────────────────────────────────
        val anyUntilExprDefinitions = definitionMatches.filter {
            it.strategy == ListenStrategy.ANY && it.until is CachedUntilCondition.Expression
        }
        if (anyUntilExprDefinitions.isNotEmpty()) {
            affectedCount += processAnyWithUntilExpressionDirect(anyUntilExprDefinitions, event)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ANY + until(event) - Direct INSERT only (accumulation)
        // Bulk insert events without loading listeners
        // Completion happens when termination event arrives (via processTerminationEventsDirect)
        // ─────────────────────────────────────────────────────────────────────────────
        val anyUntilEventDefinitions = definitionMatches.filter {
            it.strategy == ListenStrategy.ANY && it.until is CachedUntilCondition.Event
        }
        if (anyUntilEventDefinitions.isNotEmpty()) {
            processAnyWithUntilEventDirect(anyUntilEventDefinitions, event)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Handle termination events for ANY + until(event) strategy - Direct UPDATE
        // Completes listeners with all their accumulated events (excluding termination event)
        // ─────────────────────────────────────────────────────────────────────────────
        if (definitionsUntilEvent.isNotEmpty()) {
            affectedCount += processTerminationEventsDirect(definitionsUntilEvent)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ALL - Requires SELECT for accumulation logic
        // Query listeners only for ALL strategy that needs filter-index tracking
        // ─────────────────────────────────────────────────────────────────────────────
        val allDefinitions = definitionMatches.filter { it.strategy == ListenStrategy.ALL }

        // Query listeners only for ALL strategy
        val listenerMatches = if (allDefinitions.isNotEmpty()) {
            queryListenersForDefinitions(allDefinitions)
        } else {
            emptyList()
        }

        // Extract event data once per readAs mode (avoid repeated parsing)
        val eventDataByReadAs = listenerMatches.map { it.readAs }.toSet().associateWith { readAs ->
            extractEventContent(event, readAs)
        }

        // Strategy: ALL - Accumulate events until all filters have matched
        val allMatches = listenerMatches.filter { it.strategy == ListenStrategy.ALL }
        if (allMatches.isNotEmpty()) {
            affectedCount += processAll(allMatches, eventDataByReadAs)
        }

        logger.debug { "CloudEvent processing complete: $affectedCount listeners affected" }
        return affectedCount
    }

    /**
     * Queries active listeners for the given definition matches.
     *
     * @param definitions Definition matches from Step 1
     * @return List of listener matches with full context
     */
    private suspend fun queryListenersForDefinitions(definitions: List<DefinitionMatch>): List<ListenerMatch> {
        // Build unique query keys
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()

        // Batch query all matching listeners
        val listeners = listenerRepository.findByKeys(queryKeys)

        if (listeners.isEmpty()) {
            return emptyList()
        }

        // Create a lookup map: (workflowInfo, position) -> list of DefinitionMatch
        val definitionsByPosition = definitions.groupBy { def ->
            Pair(def.workflowInfo, def.nodePosition)
        }

        // Build ListenerMatch results by joining listeners with their definition context
        val results = mutableListOf<ListenerMatch>()

        for (listener in listeners) {
            val positionKey = Pair(
                WorkflowInfo(listener.workflowNamespace, listener.workflowName, listener.workflowVersion),
                listener.nodePosition
            )

            val matchingDefinitions = definitionsByPosition[positionKey] ?: continue

            // For ONE/ANY (without until): only need one match (first filter)
            // For ALL and ANY+until: need to emit one ListenerMatch per matched filter
            for (definition in matchingDefinitions) {
                results.add(
                    ListenerMatch(
                        listener = listener,
                        strategy = definition.strategy,
                        readAs = definition.readAs,
                        filterIndex = definition.filterIndex,
                        totalFilters = definition.totalFilters,
                        until = definition.until
                    )
                )

                // For ONE/ANY without until, only need one match per listener
                val needsAllMatches = definition.strategy == ListenStrategy.ALL ||
                    (definition.strategy == ListenStrategy.ANY && definition.until != null)
                if (!needsAllMatches) break
            }
        }

        return results
    }

    /**
     * Processes termination events for ANY + until(event) strategy using direct UPDATE.
     *
     * Completes listeners with all their accumulated events (excluding the termination event)
     * in a single UPDATE with subquery, avoiding loading listeners into memory.
     *
     * @param definitions Termination definition matches
     * @return Number of listeners that were completed
     */
    private suspend fun processTerminationEventsDirect(
        definitions: List<TerminationDefinitionMatch>
    ): Int {
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()

        val completed = listenerRepository.markTerminatedByKeys(queryKeys)

        if (completed > 0) {
            logger.info { "Direct UPDATE marked $completed listeners ready for completion (termination event received)" }
        }

        return completed
    }

    /**
     * Processes ONE/ANY strategy using direct UPDATE (no SELECT).
     *
     * Groups definitions by readAs mode and executes one UPDATE per group directly
     * on the database without first loading listeners into memory. This avoids
     * loading potentially millions of listeners.
     *
     * @param definitions Definition matches for ONE or ANY (without until) strategy
     * @param event The CloudEvent being processed
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processOneAnyDirect(
        definitions: List<DefinitionMatch>,
        event: CloudEvent,
    ): Int {
        // Group by readAs mode (different event data format)
        val byReadAs = definitions.groupBy { it.readAs }

        var totalAffected = 0

        for ((readAs, defGroup) in byReadAs) {
            val eventData = extractEventContent(event, readAs)
            // Store as JSON array (spec requires output to be an array even for ONE/ANY)
            val eventArray = JsonArray(listOf(eventData))
            val eventJson = Json.encodeToString(eventArray)

            val queryKeys = defGroup.map { it.toQueryKey() }.distinct()

            val affected = listenerRepository.markReadyForCompletionByKeys(
                keys = queryKeys,
                event = eventJson
            )

            if (affected > 0) {
                logger.info { "Direct UPDATE marked $affected ONE/ANY listeners ready for completion (readAs=$readAs)" }
            }
            totalAffected += affected
        }

        return totalAffected
    }

    /**
     * Processes ANY + until(expression) strategy using direct INSERT + cursor streaming.
     *
     * This method avoids loading millions of listeners into memory by:
     * 1. Bulk INSERT events using INSERT...SELECT (one query, no memory load)
     * 2. Stream listeners with accumulated events using cursor (constant memory)
     * 3. Evaluate expression on each streamed listener
     * 4. Batch UPDATE ready listeners (periodic flushes)
     *
     * Idempotency is ensured by:
     * - cloudevent_id column with UNIQUE constraint prevents duplicate events on retry
     * - WHERE guards on UPDATE prevent double-completion of listeners
     *
     * @param definitions Definition matches for ANY + until(expression) strategy
     * @param event The CloudEvent being processed
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processAnyWithUntilExpressionDirect(
        definitions: List<DefinitionMatch>,
        event: CloudEvent
    ): Int {
        // Group by readAs mode for bulk insert
        val byReadAs = definitions.groupBy { it.readAs }

        var totalAffected = 0

        for ((readAs, defGroup) in byReadAs) {
            val eventData = extractEventContent(event, readAs)
            val eventJson = Json.encodeToString(eventData)

            val queryKeys = defGroup.map { it.toQueryKey() }.distinct()

            // Build map of (workflowInfo, position) -> until expression
            val untilExpressions = defGroup.associate { def ->
                Pair(def.workflowInfo, def.nodePosition) to (def.until as CachedUntilCondition.Expression).expression
            }

            // Step 1: Bulk INSERT events for all matching listeners (one query, no memory load)
            val cloudEventId = event.id
            val inserted = listenerEventRepository.bulkInsertEventsForKeys(
                keys = queryKeys,
                cloudEventId = cloudEventId,
                eventJson = eventJson
            )
            logger.debug { "Bulk inserted $inserted events for ANY+until(expr) listeners (readAs=$readAs)" }

            // Step 2: Stream listeners with accumulated events and evaluate expressions
            val readyListeners = mutableMapOf<IDV7, String>()
            val batchSize = 1000

            listenerRepository.streamListenersWithEvents(queryKeys).collect { (listener, accumulatedEvents) ->
                val positionKey = Pair(listener.workflowInfo, listener.nodePosition)
                val untilExpr = untilExpressions[positionKey] ?: return@collect

                // Build JSON array of accumulated events
                val eventsArray = JsonArray(accumulatedEvents.map { Json.parseToJsonElement(it) })

                // Evaluate the until expression
                val shouldComplete = try {
                    evaluateUntilExpression(untilExpr, eventsArray)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to evaluate until expression for listener ${listener.id}: $untilExpr" }
                    false
                }

                if (shouldComplete) {
                    readyListeners[listener.id] = Json.encodeToString(eventsArray)
                    logger.debug { "ANY+until expression evaluated to true for listener ${listener.id}" }

                    // Flush batch if size reached
                    if (readyListeners.size >= batchSize) {
                        val flushed = listenerRepository.batchMarkReadyForCompletionFromEvents(readyListeners.toMap())
                        totalAffected += flushed
                        readyListeners.clear()
                        logger.info { "Flushed $flushed ANY+until(expr) listeners ready for completion" }
                    }
                }
            }

            // Flush remaining
            if (readyListeners.isNotEmpty()) {
                val flushed = listenerRepository.batchMarkReadyForCompletionFromEvents(readyListeners)
                totalAffected += flushed
                logger.info { "Flushed final $flushed ANY+until(expr) listeners ready for completion" }
            }
        }

        return totalAffected
    }

    /**
     * Processes ANY + until(event) strategy accumulation using direct INSERT.
     *
     * This method only handles event accumulation - completion happens when a
     * termination event arrives (via processTerminationEventsDirect).
     *
     * Uses INSERT...SELECT to bulk insert events without loading listeners into memory.
     *
     * @param definitions Definition matches for ANY + until(event) strategy
     * @param event The CloudEvent being processed
     */
    private suspend fun processAnyWithUntilEventDirect(
        definitions: List<DefinitionMatch>,
        event: CloudEvent
    ) {
        // Group by readAs mode for bulk insert
        val byReadAs = definitions.groupBy { it.readAs }

        for ((readAs, defGroup) in byReadAs) {
            val eventData = extractEventContent(event, readAs)
            val eventJson = Json.encodeToString(eventData)

            val queryKeys = defGroup.map { it.toQueryKey() }.distinct()

            // Bulk INSERT events for all matching listeners (one query, no memory load)
            val inserted = listenerEventRepository.bulkInsertEventsForKeys(
                keys = queryKeys,
                cloudEventId = event.id,
                eventJson = eventJson
            )
            logger.debug { "Bulk inserted $inserted events for ANY+until(event) listeners (readAs=$readAs, accumulating)" }
        }

        // Accumulation doesn't complete listeners - just stores events
        // Completion happens when a termination event arrives (via processTerminationEventsDirect)
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
     * Processes ALL strategy matches.
     *
     * For ALL strategy:
     * 1. Batch INSERT events into lemline_listener_events table (idempotent)
     * 2. Direct UPDATE with subquery to mark completed listeners (COUNT >= total_filters)
     *
     * This approach:
     * - Uses insert-only pattern (no read-modify-write race conditions)
     * - Idempotent inserts via ON CONFLICT DO NOTHING
     * - Direct UPDATE avoids loading accumulated events into memory
     *
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processAll(
        matches: List<ListenerMatch>,
        eventDataByReadAs: Map<ListenAndReadAs, JsonElement>,
    ): Int {
        // Group by (filterIndex, readAs) for batch insert
        data class AllGroupKey(val filterIndex: Int, val readAs: ListenAndReadAs)

        val byGroup = matches.groupBy { AllGroupKey(it.filterIndex, it.readAs) }

        // Build all event models for batch insert
        val eventModels = mutableListOf<ListenerEventModel>()
        val queryKeySet = mutableSetOf<ListenerQueryKey>()

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
                        cloudEventId = null,  // null for ALL (idempotency via filter_index)
                        event = eventJson
                    )
                )

                // Collect query keys for completion check
                queryKeySet.add(
                    ListenerQueryKey(
                        workflowInfo = WorkflowInfo(
                            match.listener.workflowNamespace,
                            match.listener.workflowName,
                            match.listener.workflowVersion
                        ),
                        position = match.listener.nodePosition!!,
                        correlationValuesJson = match.listener.correlationValues
                    )
                )
            }
        }

        if (eventModels.isEmpty()) return 0

        // Step 1: Batch INSERT events (idempotent - duplicates ignored)
        val inserted = listenerEventRepository.insert(eventModels)
        logger.debug { "Inserted $inserted events for ALL strategy listeners" }

        // Step 2: Direct UPDATE with subquery to mark completed listeners
        // The database checks COUNT >= total_filters and aggregates events
        val completed = listenerRepository.markAllCompletedByKeys(queryKeySet.toList())

        if (completed > 0) {
            logger.info { "Direct UPDATE marked $completed ALL listeners ready for completion (all filters matched)" }
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
