// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.CachedUntilCondition
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import io.cloudevents.CloudEvent
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Service for handling listener-related operations.
 *
 * Provides business logic for:
 * - Processing incoming CloudEvents and matching them to listeners
 * - Starting listeners (creating listener records for CloudEvent consumption)
 * - Handling foreach iteration completion
 * - Evaluating until conditions for listener completion
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ListenerService {

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    lateinit var listenerEventRepository: ListenerEventRepository

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var definitionListenService: DefinitionListenService

    private val logger = logger()

    /**
     * Handles listen started by creating a listener row in the database.
     *
     * The listener stores all data needed for CloudEvent matching:
     * - Workflow identity (namespace, name, version) for locating the listen task
     * - Workflow position for locating the listen task in the workflow tree
     * - Workflow instance identity (for resuming when events match)
     * - Progress tracking (for ALL strategy and accumulation mode)
     *
     * @param instanceMessage The instance message containing the listen started event
     * @return true if the listener was created, false if it already existed (idempotent)
     */
    suspend fun handleListenStarted(instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>): Boolean {
        val state = instanceMessage.workflowState
        val config = state.config
        val listenerId = state.nodeStack.deriveIdempotentId("-listen")

        // Create listener model - workflow identity derived from instanceMessage
        val listener = ListenerModel(
            id = listenerId,
            instanceMessage = instanceMessage,
            listenerStrategy = ListenerStrategy.from(config),
            timeoutAt = config.timeoutAt,
        )

        // Calculate correlation values from expect expressions
        listener.correlationValues = calculateCorrelationValues(config)

        // Set totalFilters (enables direct UPDATE optimization)
        listener.filtersCount = config.filters.size

        // Lookup hasForeach and until configuration from cached workflow definition
        val listenTask = WorkflowCache.getListenTasks(instanceMessage.workflowInfo)
            .find { it.nodePosition == state.nodePosition }
        listener.hasForeach = listenTask?.hasForeach ?: false

        // Store until configuration for expression evaluation
        listener.hasUntil = listenTask?.until != null

        // Is until an expression?
        listener.untilExpression = (listenTask?.until as? CachedUntilCondition.Expression)?.expression

        // Insert listener into database
        val rowsInserted = listenerRepository.insert(listener)

        if (rowsInserted == 0) {
            logger.info { "Listener $listenerId already exists (idempotent insert)" }
            return false
        }

        logger.debug {
            "Listen task started: $listenerId for workflow ${instanceMessage.workflowId} " +
                "at position ${state.nodePosition}"
        }
        return true
    }

    /**
     * Handles foreach iteration completion.
     *
     * When a foreach.do completes for a single event:
     * 1. Find the currently processing event (outbox_delayed_until IS NOT NULL, outbox_completed_at IS NULL)
     * 2. Mark the event as completed with output
     * 3. The next event in FIFO sequence is triggered automatically
     *
     * @param message The instance message containing the listen foreach completed event
     */
    suspend fun handleListenForEachCompleted(message: InstanceMessage<WorkflowEvent.ListenForEachCompleted>) {
        val state = message.workflowState
        val listenPosition = state.nodePosition
        val iterationOutput = state.output

        logger.debug { "ListenForEachCompleted: listenPosition=$listenPosition" }

        databaseConfig.withTransaction { conn ->
            // Find listener by workflowId and position
            val listener = listenerRepository.findByWorkflowIdAndPosition(
                message.workflowId,
                listenPosition,
                conn
            ) ?: error("Listener not found for workflow ${message.workflowId} at position $listenPosition")

            // Find the currently processing event
            val processingEvent = listenerEventRepository.findProcessingEvent(listener.id, conn)
                ?: error("No processing event found for listener ${listener.id}")

            // Mark event as completed with output (this also triggers the next event)
            val outputJson = LemlineJson.encodeToString(iterationOutput)

            listenerEventRepository.markCompletedWithOutput(
                processingEvent.listenerId,
                processingEvent.eventId,
                outputJson,
                conn
            )

            logger.info {
                "Foreach event (listenerId=${processingEvent.listenerId}, eventId=${processingEvent.eventId}) " +
                    "completed for listener ${listener.id}"
            }

            // Note: Listener completion is handled by ListenerCompletionOutbox.batchMarkReady()
        }
    }

    /**
     * Calculates correlation values by evaluating `expect` expressions from filters
     * against the correlation context.
     *
     * Returns a JSON string with sorted keys for consistent database comparison,
     * or null if no correlations with expect expressions are defined.
     *
     * @param config The listen configuration containing filters and correlation context
     * @return JSON string of correlation values, or null if no correlations
     */
    private fun calculateCorrelationValues(config: ListenConfig): String? {
        val correlationContext = config.correlationContext ?: return null
        if (correlationContext !is JsonObject) return null

        // Collect all correlation expect expressions from all filters
        val expectExpressions = mutableMapOf<String, String>()
        for (filter in config.filters) {
            filter.correlations?.forEach { (key, correlateValue) ->
                correlateValue.expect?.let { expectExpr ->
                    expectExpressions[key] = expectExpr
                }
            }
        }

        if (expectExpressions.isEmpty()) return null

        // Evaluate each expect expression against the correlation context
        val evaluatedValues = mutableMapOf<String, String>()
        for ((key, expectExpr) in expectExpressions) {
            try {
                val trimmedExpr = if (ExpressionUtils.isExpr(expectExpr)) {
                    ExpressionUtils.trimExpr(expectExpr)
                } else {
                    expectExpr
                }

                val result = with(LemlineJson) {
                    val inputNode = correlationContext.toJsonNode()
                    val scope = JsonObject(emptyMap()).toJsonNode() as ObjectNode
                    JQExpression.eval(inputNode, trimmedExpr, scope).toJsonElement()
                }

                val value = when (result) {
                    is JsonPrimitive -> result.contentOrNull
                    else -> result.toString()
                }

                if (value != null) {
                    evaluatedValues[key] = value
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate correlation expect expression '$expectExpr' for key '$key'" }
            }
        }

        if (evaluatedValues.isEmpty()) return null

        // Serialize with sorted keys for consistent database comparison
        val sortedEntries = evaluatedValues.entries.sortedBy { it.key }
        return buildJsonObject {
            for ((key, value) in sortedEntries) {
                put(key, value)
            }
        }.let { Json.encodeToString(it) }
    }

    // ========================================
    // CloudEvent Processing
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
            affectedCount += insertForOneAny(oneAnyTasks, event)
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
            affectedCount += insertForAccumulating(accumulatingTasks, event)
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
    private suspend fun insertForAccumulating(
        definitions: List<MatchingListenTask>,
        event: CloudEvent,
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = CloudEventService.serialize(event)
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
    private suspend fun processUntilExpressions(
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
            val eventsArray = JsonArray(accumulatedEvents.map { CloudEventService.extractData(it) })

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
}
