// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.Logger
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.workflows.CachedUntilCondition
import com.lemline.runner.definitions.DefinitionListenService
import com.lemline.runner.definitions.MatchingListenTask
import com.lemline.runner.definitions.MatchingListenTaskUntilEvent
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.listeners.ListenerEventRepository
import com.lemline.runner.listeners.ListenerRepository
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
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Handles CloudEvent processing and listener evaluation, including matching events
 * to active listeners, inserting events, and evaluating termination and until conditions.
 * This class focuses on the processing logic for various event handling strategies.
 *
 * Fields:
 * - `definitionListenService`: Service that manages listener definitions.
 * - `listenerRepository`: Repository for managing active listeners.
 * - `metrics`: Tracks metrics for event processing.
 * - `logger`: Logs messages and processing details.
 * - `listenerEventRepository`: Repository for emitted listener events.
 * - `cloudEventFormat`: Manages CloudEvent serialization and deserialization.
 * - `onCompleteTest`: Test hook for on-completion scenarios.
 * - `onFailureTest`: Test hook for on-failure scenarios.
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

    // Test hooks
    override var onCompleteTest: (Message<String>, CloudEvent?) -> Unit = { _, _ -> }
    override var onFailureTest: (Message<String>, Throwable?) -> Unit = { _, _ -> }

    // ========================================
    // MessageHandler implementation
    // ========================================

    override suspend fun Message<String>.deserialize(): CloudEvent {
        return CloudEventService.deserialize(payload)
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
    private suspend fun handleCloudEvent(event: CloudEvent): Int {
        val eventType: String = event.type

        logger.debug { "Processing CloudEvent: type=$eventType, source=${event.source}, id=${event.id}" }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 1: Match event against workflow definitions (in-memory, no database)
        // ═══════════════════════════════════════════════════════════════════════════════

        var eventData: JsonElement? = null

        val eventDataProvider: () -> JsonElement = {
            when (eventData) {
                null -> CloudEventService.parseData(event).also { eventData = it }
                else -> eventData
            }
        }

        // No database request are done here, we just inspect the workflow definition
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
            it.strategy == ListenStrategy.ONE || (it.strategy == ListenStrategy.ANY && it.until == null)
        }
        if (oneAnyTasks.isNotEmpty()) {
            affectedCount += insertForOneAny(oneAnyTasks, event, eventDataProvider)
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // Path 2: Accumulating (ALL or ANY+until) - Accumulate events
        // Uses batchInsertForAccumulating() which:
        // - Inserts events with sequence numbers for FIFO ordering
        // - Triggers first event for foreach listeners
        // ─────────────────────────────────────────────────────────────────────────────
        val accumulatingTasks = matchingListenTasks.filter {
            it.strategy == ListenStrategy.ALL ||
                (it.strategy == ListenStrategy.ANY && it.until != null)
        }
        if (accumulatingTasks.isNotEmpty()) {
            affectedCount += insertForAccumulating(accumulatingTasks, event, eventDataProvider)
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
        val exprTasks = accumulatingTasks.filter { it.until is CachedUntilCondition.Expression }
        if (exprTasks.isNotEmpty()) {
            affectedCount += ProcessUntilExpressions(exprTasks)
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
        @Suppress("UNUSED_PARAMETER") eventDataProvider: () -> JsonElement
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = serializeCloudEvent(event)
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
    private suspend fun insertForAccumulating(
        definitions: List<MatchingListenTask>,
        event: CloudEvent,
        @Suppress("UNUSED_PARAMETER") eventDataProvider: () -> JsonElement
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = serializeCloudEvent(event)
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()

        val inserted = listenerEventRepository.batchInsertForAccumulating(
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
     * - Sets `ready_at = NOW` for matching listeners
     * - Listener completion is handled by `ListenerCompletionOutbox`
     *
     * @param listenTasks Termination definition matches
     * @return Number of listeners marked ready
     */
    private suspend fun processUntilEvent(
        listenTasks: List<MatchingListenTaskUntilEvent>
    ): Int {
        val queryKeys = listenTasks.map { it.toQueryKey() }.distinct()

        val markedReady = listenerRepository.batchMarkReadyByUntilEvent(queryKeys)
        if (markedReady > 0) {
            logger.info { "Marked $markedReady listeners ready for completion (termination event received)" }
        }

        return markedReady
    }

    /**
     * Evaluates until expressions for ANY+until(expression) listeners.
     *
     * Uses `findListenersForUntilEvaluation()` to get listeners with accumulated events,
     * evaluates expressions, and marks ready using `markReady()`.
     *
     * @param definitions Definition matches with expression-based until conditions
     * @return Number of listeners that were marked ready
     */
    private suspend fun ProcessUntilExpressions(
        definitions: List<MatchingListenTask>
    ): Int {
        // Build map of (workflowInfo, position) -> until expression for matching
        val untilExpressions = definitions.associate { def ->
            Pair(def.workflowInfo, def.nodePosition) to (def.until as CachedUntilCondition.Expression).expression
        }

        // Get listeners with accumulated events that have until expressions
        val listenersWithEvents = listenerRepository.findListenersForUntilEvaluation()

        var totalMarkedReady = 0

        for ((listener, accumulatedEvents) in listenersWithEvents) {
            // Find matching expression for this listener
            val positionKey = Pair(listener.workflowInfo, listener.nodePosition)
            val untilExpr = untilExpressions[positionKey]
                ?: listener.untilExpression  // Fallback to stored expression
                ?: continue  // Skip if no expression found

            // Build JSON array of event DATA (extract from stored CloudEvents)
            // Until expressions operate on event data, not the full envelope
            val eventsArray = JsonArray(accumulatedEvents.map { extractDataFromStoredEvent(it) })

            // Evaluate the until expression
            val shouldComplete = try {
                evaluateUntilExpression(untilExpr, eventsArray)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate until expression for listener ${listener.id}: $untilExpr" }
                false
            }

            if (shouldComplete) {
                val marked = listenerRepository.markReady(listener.id)
                if (marked > 0) {
                    totalMarkedReady++
                    logger.debug { "ANY+until expression evaluated to true for listener ${listener.id}" }
                }
            }
        }

        if (totalMarkedReady > 0) {
            logger.info { "Marked $totalMarkedReady ANY+until(expr) listeners ready for completion" }
        }

        return totalMarkedReady
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
     * Serializes a CloudEvent to JSON string for storage.
     * Always stores the complete CloudEvent - readAs transformation happens at completion time.
     */
    private fun serializeCloudEvent(event: CloudEvent): String {
        return CloudEventService.serialize(event)
    }

    /**
     * Extracts the data portion from a stored CloudEvent JSON for until expression evaluation.
     * Until expressions operate on the event data, not the full envelope.
     */
    private fun extractDataFromStoredEvent(eventJson: String): JsonElement =
        CloudEventService.extractData(eventJson)
}
