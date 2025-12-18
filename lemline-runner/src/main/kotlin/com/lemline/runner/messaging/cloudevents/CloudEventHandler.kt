// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.Logger
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.definitions.CachedUntilCondition
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenStrategy
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.definitions.MatchingListenTask
import com.lemline.runner.definitions.MatchingListenTaskUntilEvent
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.repositories.ListenerEventRepository
import com.lemline.runner.repositories.ListenerQueryKey
import com.lemline.runner.repositories.ListenerRepository
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
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
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Handles incoming CloudEvents by matching them against active listeners
 * and marking matched listeners ready for completion.
 *
 * Implements [MessageHandler] for integration with the subscriber infrastructure.
 * Returns null from [handle] to skip emit (CloudEvents don't produce outbound messages).
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
    override val metrics: CloudEventSubscriberMetrics,
) : MessageHandler<CloudEvent> {

    override val logger: Logger = logger()

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    private val cloudEventFormat = JsonFormat()

    // Test hooks
    override var onCompleteTest: (Message<String>, CloudEvent?) -> Unit = { _, _ -> }
    override var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }

    // ========================================
    // MessageHandler implementation
    // ========================================

    override suspend fun Message<String>.deserialize(): CloudEvent {
        return cloudEventFormat.deserialize(payload.toByteArray())
    }

    override suspend fun handle(current: CloudEvent): CloudEvent? {
        handleCloudEvent(current)
        // Return null to skip emit - CloudEvents don't produce outbound messages
        return null
    }

    // Never called since handle() returns null
    override suspend fun serialize(current: CloudEvent, next: CloudEvent): String {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // Never called since handle() returns null
    override suspend fun emit(payload: String, idempotentKey: IDV7) {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // Never called since handle() returns null
    override fun deriveIdempotentKey(next: CloudEvent): IDV7 {
        throw UnsupportedOperationException("CloudEvents don't emit messages")
    }

    // ========================================
    // CloudEvent processing logic
    // ========================================

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
    private suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 1: Match event against workflow definitions (in-memory, no database)
        //
        // For each workflow definition with a listen task:
        //   - Check if the event matches any of the task's filters
        //   - Extract correlation values using filter's correlate.from expressions
        // ═══════════════════════════════════════════════════════════════════════════════

        var eventData: JsonElement? = null

        val eventDataProvider: () -> JsonElement = {
            when (eventData) {
                null -> event.parseData().also { eventData = it }
                else -> eventData
            }
        }

        val matchingListenTasks = definitionListenService.findMatchingListenTasks(event, eventDataProvider)
        val matchingUntilEvent = definitionListenService.findMatchingUntilEvents(event, eventDataProvider)

        if (matchingListenTasks.isEmpty() && matchingUntilEvent.isEmpty()) {
            logger.trace { "No matching definitions for event: $event" }
            return 0
        }

        logger.debug {
            "Definition matching complete: ${matchingListenTasks.size} listen tasks match, and " +
                "${matchingUntilEvent.size} 'until' definitions match for event: $event"
        }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 2: Process each strategy independently
        //
        // ONE/ANY (without until): Direct UPDATE without SELECT (avoids loading millions of rows)
        // ANY+until, ALL: Still requires SELECT for event accumulation logic
        // ═══════════════════════════════════════════════════════════════════════════════

        var affectedCount = 0

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ONE or ANY (without until) - Fire and forget
        // Complete listener immediately on first matching event
        // ─────────────────────────────────────────────────────────────────────────────
        val oneOrAnyListenTasks = matchingListenTasks.filter {
            it.strategy == ListenStrategy.ONE ||
                (it.strategy == ListenStrategy.ANY && it.until == null)
        }
        if (oneOrAnyListenTasks.isNotEmpty()) {
            affectedCount += processOneAny(oneOrAnyListenTasks, event, eventDataProvider)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ALL
        // Bulk insert events and check completion without loading listeners into memory
        // ─────────────────────────────────────────────────────────────────────────────
        val allListenTasks = matchingListenTasks.filter {
            it.strategy == ListenStrategy.ALL
        }
        if (allListenTasks.isNotEmpty()) {
            affectedCount += processAll(allListenTasks, event, eventDataProvider)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ANY + until (expression or event)
        // Accumulate events; for expression: evaluate after each event
        // For event: completion happens when termination event arrives
        // ─────────────────────────────────────────────────────────────────────────────
        val anyWithUntilListenTasks = matchingListenTasks.filter {
            it.strategy == ListenStrategy.ANY && it.until != null
        }
        if (anyWithUntilListenTasks.isNotEmpty()) {
            affectedCount += processAnyWithUntil(anyWithUntilListenTasks, event, eventDataProvider)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Strategy: ANY + until(event)
        // Completes listeners with all their accumulated events (excluding termination event)
        // ─────────────────────────────────────────────────────────────────────────────
        if (matchingUntilEvent.isNotEmpty()) {
            affectedCount += processWithMatchingUntilEvent(matchingUntilEvent)
        }

        logger.debug { "CloudEvent processing complete: $affectedCount listeners affected" }
        return affectedCount
    }

    /**
     * Parses the CloudEvent data payload to JsonElement.
     */
    fun CloudEvent.parseData(): JsonElement {
        data ?: return JsonNull

        return try {
            val bytes = data!!.toBytes()
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
     * Processes ONE/ANY strategy using direct UPDATE (no SELECT).
     *
     * Groups definitions by readAs mode and executes one UPDATE per group directly
     * on the database without first loading listeners into memory. This avoids
     * loading potentially millions of listeners.
     *
     * @param listenTasks Definition matches for ONE or ANY (without until) strategy
     * @param event The CloudEvent being processed
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processOneAny(
        listenTasks: List<MatchingListenTask>,
        event: CloudEvent,
        eventDataProvider: () -> JsonElement
    ): Int {
        // Group by readAs mode (different event data format)
        val byReadAs = listenTasks.groupBy { it.readAs }

        var totalAffected = 0

        for ((readAs, tasks) in byReadAs) {
            val eventData = extractEventContent(readAs, event, eventDataProvider)
            // Store as JSON array (spec requires output to be an array even for ONE/ANY)
            val eventArray = JsonArray(listOf(eventData))
            val eventJson = Json.encodeToString(eventArray)

            val queryKeys = tasks.map { it.toQueryKey() }.distinct()

            val affected = listenerRepository.markCompletedByKeys(
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
     * Processes ALL strategy using direct INSERT...SELECT + UPDATE (no SELECT into memory).
     *
     * This method avoids loading millions of listeners into memory by:
     * 1. Grouping definitions by readAs for batch processing (filterIndex is in query keys)
     * 2. Bulk INSERT events using INSERT...SELECT (one query per readAs mode, no memory load)
     * 3. Direct UPDATE with subquery to mark complete listeners (COUNT >= filters_count)
     * 4. For foreach-enabled listeners: schedule events for foreach processing instead
     *
     * Idempotency is ensured by:
     * - filter_index column with UNIQUE(listener_id, filter_index) constraint
     * - WHERE guards on UPDATE prevent double-completion of listeners
     *
     * @param definitions Definition matches for ALL strategy
     * @param event The CloudEvent being processed
     * @return Number of listeners that were marked for completion
     */
    private suspend fun processAll(
        definitions: List<MatchingListenTask>,
        event: CloudEvent,
        eventDataProvider: () -> JsonElement
    ): Int {
        // Group by readAs only (filterIndex is now part of ListenerQueryKey)
        val byReadAs = definitions.groupBy { it.readAs }

        // Collect query keys separately for foreach and non-foreach
        val allQueryKeys = mutableSetOf<ListenerQueryKey>()
        val foreachQueryKeys = mutableSetOf<ListenerQueryKey>()

        // Step 1: Bulk INSERT events for each readAs group
        for ((readAs, tasks) in byReadAs) {
            val eventData = extractEventContent(readAs, event, eventDataProvider)
            val eventJson = LemlineJson.encodeToString(eventData)

            val queryKeys = tasks.map { it.toQueryKey() }.distinct()
            allQueryKeys.addAll(queryKeys)

            // Collect foreach keys separately
            tasks.filter { it.hasForeach }.forEach { foreachQueryKeys.add(it.toQueryKey()) }

            val inserted = listenerEventRepository.bulkInsertEventsForAllStrategy(
                keys = queryKeys,
                eventJson = eventJson
            )

            logger.debug { "Bulk inserted $inserted events for ALL strategy (readAs=$readAs)" }
        }

        if (allQueryKeys.isEmpty()) return 0

        var completed = 0

        // Step 2a: Direct UPDATE for non-foreach listeners (immediate completion)
        val nonForeachCompleted = listenerRepository.markAllCompletedByKeys(allQueryKeys.toList())
        if (nonForeachCompleted > 0) {
            logger.info { "Direct UPDATE marked $nonForeachCompleted ALL listeners ready for completion (all filters matched)" }
            completed += nonForeachCompleted
        }

        // Step 2b: For foreach-enabled listeners - schedule events and mark logically completed
        if (foreachQueryKeys.isNotEmpty()) {
            // Set outbox_scheduled_for on events for foreach processing
            val scheduled = listenerEventRepository.setForeachScheduledForKeys(foreachQueryKeys.toList())
            logger.debug { "Scheduled $scheduled events for foreach processing (ALL strategy)" }

            // Trigger first event for listeners not already processing
            val triggered = listenerEventRepository.triggerFirstEventForForeachListeners(foreachQueryKeys.toList())
            logger.debug { "Triggered first event for $triggered foreach listeners (ALL strategy)" }

            // Mark foreach listeners as logically completed (they'll complete after foreach finishes)
            val foreachCompleted = listenerRepository.markForeachAllCompletedByKeys(foreachQueryKeys.toList())
            if (foreachCompleted > 0) {
                logger.info { "Marked $foreachCompleted ALL foreach listeners as logically completed (foreach will process)" }
            }
        }

        return completed
    }

    /**
     * Processes termination events for ANY + until(event) strategy using direct UPDATE.
     *
     * Completes listeners with all their accumulated events (excluding the termination event)
     * in a single UPDATE with subquery, avoiding loading listeners into memory.
     * For foreach-enabled listeners, marks them as logically completed instead.
     *
     * @param definitions Termination definition matches
     * @return Number of listeners that were completed
     */
    private suspend fun processWithMatchingUntilEvent(
        definitions: List<MatchingListenTaskUntilEvent>
    ): Int {
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()
        val foreachQueryKeys = definitions.filter { it.hasForeach }.map { it.toQueryKey() }.distinct()

        // Mark non-foreach listeners ready for immediate completion
        val completed = listenerRepository.markTerminatedByKeys(queryKeys)
        if (completed > 0) {
            logger.info { "Direct UPDATE marked $completed listeners ready for completion (termination event received)" }
        }

        // Mark foreach-enabled listeners as logically completed (they'll complete after foreach finishes)
        if (foreachQueryKeys.isNotEmpty()) {
            val foreachCompleted = listenerRepository.markForeachTerminatedByKeys(foreachQueryKeys)
            if (foreachCompleted > 0) {
                logger.info { "Marked $foreachCompleted foreach listeners as logically completed (termination event received)" }
            }
        }

        return completed
    }


    /**
     * Processes ANY + until strategy (both expression and event variants).
     *
     * This unified method handles event accumulation for all ANY+until strategies:
     * 1. Bulk INSERT events using INSERT...SELECT (one query, no memory load)
     * 2. For foreach-enabled listeners: schedule events for foreach processing
     * 3. For expression-based until: stream listeners and evaluate expressions
     * 4. For event-based until: accumulation only (completion via processWithMatchingUntilEvent)
     *
     * Idempotency is ensured by:
     * - cloudevent_id column with UNIQUE constraint prevents duplicate events on retry
     * - WHERE guards on UPDATE prevent double-completion of listeners
     *
     * @param definitions Definition matches for ANY + until strategy (expression or event)
     * @param event The CloudEvent being processed
     * @return Number of listeners that were marked for completion (only for expression-based until)
     */
    private suspend fun processAnyWithUntil(
        definitions: List<MatchingListenTask>,
        event: CloudEvent,
        eventDataProvider: () -> JsonElement
    ): Int {
        // Group by readAs mode for bulk insert
        val byReadAs = definitions.groupBy { it.readAs }

        var totalAffected = 0

        for ((readAs, defGroup) in byReadAs) {
            val eventData = extractEventContent(readAs, event, eventDataProvider)
            val eventJson = Json.encodeToString(eventData)

            val queryKeys = defGroup.map { it.toQueryKey() }.distinct()
            val foreachQueryKeys = defGroup.filter { it.hasForeach }.map { it.toQueryKey() }.distinct()

            // Step 1: Bulk INSERT events for all matching listeners (one query, no memory load)
            val inserted = listenerEventRepository.bulkInsertEventsForKeys(
                keys = queryKeys,
                cloudEventId = event.id,
                eventJson = eventJson
            )
            logger.debug { "Bulk inserted $inserted events for ANY+until listeners (readAs=$readAs)" }

            // Step 2: For foreach-enabled listeners, schedule events for foreach processing
            if (foreachQueryKeys.isNotEmpty()) {
                val scheduled = listenerEventRepository.setForeachScheduledForKeys(foreachQueryKeys)
                logger.debug { "Scheduled $scheduled events for foreach processing (ANY+until)" }

                val triggered = listenerEventRepository.triggerFirstEventForForeachListeners(foreachQueryKeys)
                logger.debug { "Triggered first event for $triggered foreach listeners (ANY+until)" }
            }

            // Step 3: For expression-based until, stream and evaluate
            // (Event-based until completes via processWithMatchingUntilEvent when termination event arrives)
            val exprDefinitions = defGroup.filter { it.until is CachedUntilCondition.Expression }
            if (exprDefinitions.isNotEmpty()) {
                totalAffected += evaluateUntilExpressions(exprDefinitions)
            }
        }

        return totalAffected
    }

    /**
     * Evaluates until expressions for ANY+until(expression) listeners.
     *
     * Streams listeners with accumulated events using cursor (constant memory),
     * evaluates expressions, and batch updates ready listeners.
     *
     * @param definitions Definition matches with expression-based until conditions
     * @return Number of listeners that were marked for completion
     */
    private suspend fun evaluateUntilExpressions(
        definitions: List<MatchingListenTask>
    ): Int {
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()

        // Build map of (workflowInfo, position) -> until expression
        val untilExpressions = definitions.associate { def ->
            Pair(def.workflowInfo, def.nodePosition) to (def.until as CachedUntilCondition.Expression).expression
        }

        val readyListeners = mutableMapOf<IDV7, String>()
        val foreachReadyListenerIds = mutableListOf<IDV7>()
        val batchSize = 1000
        var totalAffected = 0

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
                if (listener.hasForeach) {
                    // Foreach listener: just mark as logically completed
                    foreachReadyListenerIds.add(listener.id)
                    logger.debug { "ANY+until expression evaluated to true for foreach listener ${listener.id}" }
                } else {
                    // Non-foreach listener: mark ready for immediate completion
                    readyListeners[listener.id] = Json.encodeToString(eventsArray)
                    logger.debug { "ANY+until expression evaluated to true for listener ${listener.id}" }
                }

                // Flush non-foreach batch if size reached
                if (readyListeners.size >= batchSize) {
                    val flushed = listenerRepository.batchMarkReadyForCompletionFromEvents(readyListeners.toMap())
                    totalAffected += flushed
                    readyListeners.clear()
                    logger.info { "Flushed $flushed ANY+until(expr) listeners ready for completion" }
                }
            }
        }

        // Flush remaining non-foreach listeners
        if (readyListeners.isNotEmpty()) {
            val flushed = listenerRepository.batchMarkReadyForCompletionFromEvents(readyListeners)
            totalAffected += flushed
            logger.info { "Flushed final $flushed ANY+until(expr) listeners ready for completion" }
        }

        // Mark foreach listeners as logically completed (they'll complete after foreach finishes)
        for (listenerId in foreachReadyListenerIds) {
            listenerRepository.setListenerCompleted(listenerId, true)
        }
        if (foreachReadyListenerIds.isNotEmpty()) {
            logger.info { "Marked ${foreachReadyListenerIds.size} ANY+until(expr) foreach listeners as logically completed" }
        }

        return totalAffected
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
     * Extracts the event content based on the readAs mode.
     *
     * @param event The CloudEvent
     * @param readAs The extraction mode (DATA, ENVELOPE, RAW)
     * @return The extracted content as JsonElement
     */
    private fun extractEventContent(
        readAs: ListenAndReadAs,
        event: CloudEvent,
        eventDataProvider: () -> JsonElement,
    ): JsonElement {
        return when (readAs) {
            ListenAndReadAs.DATA -> eventDataProvider()

            ListenAndReadAs.ENVELOPE -> {
                // Return the full event structure
                buildJsonObject {
                    // Explicitly set specversion to ensure it's present and first
                    put("specversion", event.specVersion.toString())

                    // Add data
                    put("data", eventDataProvider())

                    // Dynamically add all other context attributes (standard + extensions)
                    event.attributeNames.forEach { name ->
                        // Skip specversion if it appears in attributes to avoid redundancy
                        if (name == "specversion" || name == "data") return@forEach

                        when (val value = event.getAttribute(name)) {
                            is Number -> put(name, value)
                            is Boolean -> put(name, value)
                            // Handle URIs, Time, Strings, etc. via toString()
                            else -> value?.let { put(name, it.toString()) }
                        }
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
