// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.expressions.JQExpression
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Service for processing incoming CloudEvents and matching them to listeners.
 *
 * Handles CloudEvent routing using a 2-path architecture:
 * - ONE/ANY (without until): Complete on first event
 * - Accumulating (ALL or ANY+until): Accumulate events until condition is met
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ListenerEventService {

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    lateinit var listenerEventRepository: ListenerEventRepository

    @Inject
    lateinit var definitionListenService: DefinitionListenService

    private val logger = logger()

    /**
     * Processes an incoming CloudEvent by matching it against active listeners.
     *
     * ## Simplified Processing (2 INSERT paths)
     *
     * 1. **Definition Matching**: Query in-memory cache for matching listen tasks
     * 2. **Insert Events**: Use one of two batch INSERT methods based on strategy
     * 3. **Evaluate Until**: For ANY+until(expr), evaluate expressions and mark ready
     * 4. **Termination Events**: Mark listeners ready when termination event arrives
     *
     * Completion detection happens asynchronously in `ListenerCompletionOutbox.batchMarkReady()`.
     *
     * @param event The incoming CloudEvent
     * @return Number of events inserted (approximate affected count)
     */
    suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 1: Match event against workflow definitions (in-memory, no database)
        // ═══════════════════════════════════════════════════════════════════════════════

        var eventData: JsonElement? = null

        // use a lazy provider to parse data only if needed, and only once
        val eventDataProvider: () -> JsonElement = {
            when (eventData) {
                null -> CloudEventService.parseData(event).also { eventData = it }
                else -> eventData
            }
        }

        // No database requests are done here, we just inspect the workflow definition
        val matchingListenTasks = definitionListenService.findMatchingListenTasks(event, eventDataProvider)
        val matchingUntilEvent = definitionListenService.findMatchingUntilEvents(event, eventDataProvider)

        if (matchingListenTasks.isEmpty() && matchingUntilEvent.isEmpty()) return 0

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 2: Insert events using simplified 2-path architecture
        // ═══════════════════════════════════════════════════════════════════════════════

        var affectedCount = 0

        // ─────────────────────────────────────────────────────────────────────────────
        // Path 1: ONE/ANY (without until) - Complete on first event
        // Uses batchInsertForOneAny() which:
        // - Inserts single event with ON CONFLICT DO NOTHING
        // - Sets outbox_delayed_until = NOW for foreach listeners
        // ─────────────────────────────────────────────────────────────────────────────
        val oneAnyTasks = matchingListenTasks.filter {
            it.listenerStrategy == ListenerStrategy.ONE || it.listenerStrategy == ListenerStrategy.ANY
        }
        if (oneAnyTasks.isNotEmpty()) {
            affectedCount += insertForOneAny(oneAnyTasks, event)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Path 2: Accumulating (ALL or ANY+until) - Accumulate events
        // Uses batchInsertForAccumulating() which:
        // - Inserts events with sequence numbers for FIFO ordering
        // - Triggers first event for foreach listeners
        // ─────────────────────────────────────────────────────────────────────────────
        val accumulatingTasks = matchingListenTasks.filter {
            it.listenerStrategy in listOf(
                ListenerStrategy.ALL,
                ListenerStrategy.ANY_UNTIL_EXPR,
                ListenerStrategy.ANY_UNTIL_EVENT
            )
        }
        if (accumulatingTasks.isNotEmpty()) {
            affectedCount += insertForAllAnyUntil(accumulatingTasks, event)
        }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 3: Handle termination events and until expression evaluation
        // ═══════════════════════════════════════════════════════════════════════════════

        // ─────────────────────────────────────────────────────────────────────────────
        // Termination events: Mark listeners as ready for completion
        // ─────────────────────────────────────────────────────────────────────────────
        if (matchingUntilEvent.isNotEmpty()) {
            affectedCount += processUntilEvent(matchingUntilEvent)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Until expressions: Evaluate and mark ready if expression is true
        // ─────────────────────────────────────────────────────────────────────────────
        val exprTasks = accumulatingTasks.filter { it.listenerStrategy == ListenerStrategy.ANY_UNTIL_EXPR }
        if (exprTasks.isNotEmpty()) {
            affectedCount += processUntilExpressions(exprTasks)
        }

        logger.debug { "CloudEvent processing complete: $affectedCount events/listeners affected" }

        return affectedCount
    }

    /**
     * Inserts events for ONE/ANY strategy listeners.
     *
     * Uses `batchInsertForOneAny()` which:
     * - Inserts a single event per listener with ON CONFLICT DO NOTHING
     * - For foreach listeners: sets `outbox_delayed_until = NOW` for immediate processing
     * - Listener completion is detected by `ListenerCompletionOutbox.batchMarkReady()`
     *
     * Always stores the complete CloudEvent. The `readAs` transformation is applied
     * at completion time in `ListenerCompletionOutbox`.
     *
     * @param listenTasks Definition matches for ONE or ANY (without until) strategy
     * @param event The CloudEvent being processed
     * @return Number of events inserted
     */
    private suspend fun insertForOneAny(
        listenTasks: List<MatchingListenTask>,
        event: CloudEvent,
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = CloudEventService.serialize(event)
        val queryKeys = listenTasks.map { it.toQueryKey() }.distinct()

        val inserted = listenerEventRepository.batchInsertForOneAny(
            keys = queryKeys,
            eventId = event.id,
            eventJson = eventJson
        )

        if (inserted > 0) {
            logger.info { "Inserted $inserted events for ONE/ANY listeners" }
        }

        return inserted
    }

    /**
     * Inserts events for accumulating strategy listeners (ALL or ANY+until).
     *
     * Uses `batchInsertForAccumulating()` which:
     * - Inserts events with sequence numbers for FIFO ordering
     * - For foreach listeners: triggers first event if not already processing
     * - Listener completion is detected by `ListenerCompletionOutbox.batchMarkReady()`
     *
     * Always stores the complete CloudEvent. The `readAs` transformation is applied
     * at completion time in `ListenerCompletionOutbox`.
     *
     * @param definitions Definition matches for ALL or ANY+until strategy
     * @param event The CloudEvent being processed
     * @return Number of events inserted
     */
    private suspend fun insertForAllAnyUntil(
        definitions: List<MatchingListenTask>,
        event: CloudEvent,
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = CloudEventService.serialize(event)
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()

        val inserted = listenerEventRepository.batchInsertForAllAnyUntil(
            keys = queryKeys,
            eventId = event.id,
            eventJson = eventJson
        )

        if (inserted > 0) {
            logger.info { "Inserted $inserted events for accumulating listeners" }
        }

        return inserted
    }

    /**
     * Processes termination events for ANY + until(event) strategy.
     *
     * Uses `batchMarkReadyByTermination()` which:
     * - Sets `completed_at = NOW` for matching listeners (stops collecting events)
     * - Listener completion is handled by `ListenerCompletionOutbox`
     *
     * @param listenTasks Termination definition matches
     * @return Number of listeners marked completed
     */
    private suspend fun processUntilEvent(
        listenTasks: List<MatchingListenTaskUntilEvent>
    ): Int {
        val queryKeys = listenTasks.map { it.toQueryKey() }.distinct()

        val markedCompleted = listenerRepository.markListenerCompletedByUntilEvent(queryKeys)
        if (markedCompleted > 0) {
            logger.info { "Marked $markedCompleted listeners as completed (termination event received)" }
        }

        return markedCompleted
    }

    /**
     * Evaluates until expressions for ANY+until(expression) listeners.
     *
     * Uses `findListenersByKeysWithEvents()` to get listeners matching the current event
     * with their accumulated events, evaluates expressions, and marks completed in batch.
     *
     * @param listenTasks Definition matches used to filter which listeners to evaluate
     * @return Number of listeners that were marked as completed
     */
    private suspend fun processUntilExpressions(
        listenTasks: List<MatchingListenTask>
    ): Int {
        // Build query keys from matched listen tasks
        val queryKeys = listenTasks.map { it.toQueryKey() }

        // Get listeners matching these keys with accumulated events
        val listenersWithEvents = listenerRepository.findListenersByKeysWithEvents(queryKeys)
        if (listenersWithEvents.isEmpty()) return 0

        // Evaluate expressions and collect IDs of listeners to mark completed
        val listenersToComplete = listenersWithEvents.mapNotNull { (listener, accumulatedEvents) ->
            val untilExpr = listener.untilExpression ?: return@mapNotNull null

            // Build JSON array of event DATA (extract from stored CloudEvents)
            val eventsArray = JsonArray(accumulatedEvents.map { CloudEventService.extractData(it) })

            // Evaluate the until expression
            val shouldComplete = try {
                evaluateUntilExpression(untilExpr, eventsArray)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate until expression for listener ${listener.id}: $untilExpr" }
                false
            }

            if (shouldComplete) listener.id else null
        }

        if (listenersToComplete.isEmpty()) return 0

        // Batch mark all completed listeners
        val totalMarkedCompleted = listenerRepository.batchMarkListenersCompleted(listenersToComplete)

        if (totalMarkedCompleted > 0) {
            logger.info { "Marked $totalMarkedCompleted ANY+until(expr) listeners as completed" }
        }

        return totalMarkedCompleted
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
}
