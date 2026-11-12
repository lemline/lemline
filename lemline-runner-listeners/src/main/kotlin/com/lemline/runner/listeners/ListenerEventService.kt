// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowNamespace
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.UntilCondition
import com.lemline.core.states.ForeachState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.EventConsumptionProgress
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Clock
import kotlinx.serialization.json.Json
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
@ApplicationScoped
class ListenerEventService {

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    lateinit var listenerEventRepository: ListenerEventRepository

    @Inject
    lateinit var definitionListenService: DefinitionListenService

    @Inject
    lateinit var databaseConfig: DatabaseConfig

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
     * ## Arrival-namespace scoping
     *
     * The event arrived on one namespace's broker destination (`<prefix>-<namespace>-cloudevents`).
     * Matching is scoped to that namespace: listen tasks of other namespaces — even ones this
     * runner also follows — are filtered out before any query key is built. Since every
     * listener-matching query binds `workflow_namespace` from [ListenerQueryKey.workflowInfo],
     * this constrains all downstream SQL (inserts, until-event closing, until-expression lookups)
     * to the arrival namespace.
     *
     * @param event The incoming CloudEvent
     * @param arrivalNamespace The namespace of the destination the event arrived on
     * @return Number of events inserted (approximate affected count)
     */
    suspend fun handleCloudEvent(event: CloudEvent, arrivalNamespace: WorkflowNamespace): Int {

        logger.debug { "Processing CloudEvent (arrival namespace '$arrivalNamespace'): $event" }

        // ═══════════════════════════════════════════════════════════════════════════════
        // STEP 1: Match event against workflow definitions (in-memory, no database)
        // ═══════════════════════════════════════════════════════════════════════════════

        var eventData: JsonElement? = null

        // use a lazy provider to parse data only if needed, and only once
        val eventDataProvider: () -> JsonElement = {
            eventData ?: CloudEventService.parseData(event).also { eventData = it }
        }

        // No database requests are done here, we just inspect the workflow definition.
        // Matching is restricted to the arrival namespace: an event consumed from namespace A's
        // destination must never wake listeners of namespace B, even when both are followed.
        val matchingListenTasks = definitionListenService.findMatchingListenTasks(event, eventDataProvider)
            .filter { it.workflowInfo.namespace == arrivalNamespace }
        val matchingUntilEvent = definitionListenService.findMatchingUntilEvents(event)
            .filter { it.workflowInfo.namespace == arrivalNamespace }

        if (matchingListenTasks.isEmpty() && matchingUntilEvent.isEmpty()) return 0

        return databaseConfig.withTransaction { conn ->
            // Progress, collection and closure commit together. A delivery retried after
            // rollback cannot see termination progress without its main-stream events.
            var affectedCount = if (matchingUntilEvent.isEmpty()) 0 else processUntilEvent(matchingUntilEvent, event, conn)
            val oneAnyTasks = matchingListenTasks.filter {
                it.listenerStrategy == ListenerStrategy.ONE || it.listenerStrategy == ListenerStrategy.ANY
            }
            if (oneAnyTasks.isNotEmpty()) affectedCount += insertForOneAny(oneAnyTasks, event, conn)
            val accumulatingTasks = matchingListenTasks.filter {
                it.listenerStrategy in listOf(ListenerStrategy.ALL, ListenerStrategy.ANY_UNTIL_EXPR, ListenerStrategy.ANY_UNTIL_EVENT)
            }
            if (accumulatingTasks.isNotEmpty()) affectedCount += insertForAllAnyUntil(accumulatingTasks, event, conn)
            val exprTasks = accumulatingTasks.filter { it.listenerStrategy == ListenerStrategy.ANY_UNTIL_EXPR }
            if (exprTasks.isNotEmpty()) affectedCount += processUntilExpressions(exprTasks, conn)
            logger.debug { "CloudEvent processing complete: $affectedCount events/listeners affected" }
            affectedCount
        }
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
        connection: Connection,
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = CloudEventService.serialize(event)
        val queryKeys = listenTasks.map { it.toQueryKey() }.distinct()

        val inserted = listenerEventRepository.batchInsertForOneAny(
            keys = queryKeys,
            eventId = event.id,
            eventJson = eventJson,
            connection = connection,
        )

        if (inserted > 0) {
            logger.debug { "Inserted $inserted events for ONE/ANY listeners" }
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
        connection: Connection,
    ): Int {
        // Store complete CloudEvent - readAs transformation happens at completion time
        val eventJson = CloudEventService.serialize(event)
        val queryKeys = definitions.map { it.toQueryKey() }.distinct()

        val inserted = listenerEventRepository.batchInsertForAllAnyUntil(
            keys = queryKeys,
            eventId = event.id,
            eventJson = eventJson,
            connection = connection,
        )

        if (inserted > 0) {
            logger.debug { "Inserted $inserted events for accumulating listeners" }
        }

        return inserted
    }

    /**
     * Processes termination events for ANY + until(event) strategy.
     *
     * Locks listener rows, updates durable termination progress, and closes completed
     * subscriptions. Foreach completion and resumption remain owned by the outboxes.
     *
     * @param listenTasks Termination definition matches
     * @return Number of listeners marked completed
     */
    private suspend fun processUntilEvent(
        listenTasks: List<MatchingListenTaskUntilEvent>,
        event: CloudEvent,
        connection: Connection,
    ): Int = databaseConfig.withTransaction(connection) { conn ->
        val keys = listenTasks.map { it.toQueryKey() }.distinct()
        val listeners = listenerRepository.findUntilListenersForUpdate(keys, conn)
        var completed = 0
        for (listener in listeners) {
            val config = listener.instanceMessage.workflowState.config
            val termination = (config.until as? UntilCondition.Event)?.consumption ?: continue
            val progress = listener.untilProgress?.let { Json.decodeFromString<EventConsumptionProgress>(it) }
                ?: EventConsumptionProgress()
            val ready = progress.consume(termination, event, config.correlationContext)
            listener.untilProgress = Json.encodeToString(progress)
            if (ready) {
                listener.closedAt = Clock.System.now()
                completed++
            }
            listenerRepository.update(listener, conn)
        }
        completed
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
    /**
     * Evaluates until expressions for ANY_UNTIL_EXPR listeners.
     * Uses transaction + FOR UPDATE to prevent race when concurrent CloudEvents trigger evaluation.
     */
    private suspend fun processUntilExpressions(
        listenTasks: List<MatchingListenTask>,
        connection: Connection,
    ): Int {
        val queryKeys = listenTasks.map { it.toQueryKey() }

        return databaseConfig.withTransaction(connection) { conn ->
            val listenersWithEvents = listenerRepository.findListenersByKeysWithEvents(queryKeys, conn)
            if (listenersWithEvents.isEmpty()) return@withTransaction 0

            val listenersToComplete = listenersWithEvents.mapNotNull { (listener, accumulatedEvents) ->
                val untilExpr = listener.untilExpression ?: return@mapNotNull null

                val eventsArray = JsonArray(accumulatedEvents.map {
                    CloudEventService.parseStringAsData(it, listener.readAs)
                })

                val shouldComplete = try {
                    evaluateUntilExpression(untilExpr, eventsArray)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to evaluate until expression for listener ${listener.id}: $untilExpr" }
                    false
                }

                if (shouldComplete) listener.id else null
            }

            if (listenersToComplete.isEmpty()) return@withTransaction 0

            val totalMarkedCompleted = listenerRepository.batchMarkListenersCompleted(listenersToComplete, conn)

            if (totalMarkedCompleted > 0) {
                logger.debug { "Marked $totalMarkedCompleted ANY+until(expr) listeners as closed" }
            }

            totalMarkedCompleted
        }
    }

    internal fun evaluateUntilExpression(expression: String, events: JsonArray): Boolean {
        with(LemlineJson) {
            val inputNode = events.toJsonNode()
            val scope = JsonObject(emptyMap()).toJsonNode() as ObjectNode
            val result = JQExpression.evalOrNull(inputNode, expression, scope)?.toJsonElement()
                ?: return false
            return (result as? JsonPrimitive)?.booleanOrNull == true
        }
    }

    suspend fun handleForEachCompleted(message: InstanceMessage<WorkflowEvent.ForEachCompleted>) {
        val forEachCompleted = message.workflowState
        val forEachOutput = forEachCompleted.output
        val forEachPosition = forEachCompleted.nodePosition
        val foreachState = forEachCompleted.nodeStack.currentState as ForeachState
        val listenerId = forEachCompleted.nodeStack.pop().listenerId()
        logger.debug { "ListenForEachCompleted: listenPosition=$forEachPosition, listenerId=$listenerId, sortKey=${foreachState.index}" }

        databaseConfig.withTransaction { conn ->
            val listener = listenerRepository.findByIdForUpdate(listenerId, conn)
            if (listener == null) {
                logger.warn { "Listener not found for $listenerId - message=$message" }
                return@withTransaction
            }

            val outputJson = LemlineJson.encodeToString(forEachOutput)

            val updated = listenerEventRepository.markForeachCompleted(
                listenerId,
                foreachState.index,
                outputJson,
                conn
            )

            if (updated == 0) logger.warn {
                "Failed to mark foreach completed for listenerId=$listenerId, sortKey=${foreachState.index} - message=$message"
            }

            val listenerState = listener.workflowState as WorkflowEvent.ListenStarted
            val newContext = forEachCompleted.nodeStack.rootState.context
            if (listenerState.nodeStack.rootState.context != newContext) {
                logger.debug { "Context changed in ForEachCompleted, updating listener state" }
                listener.instanceMessage = listener.instanceMessage.copy(
                    workflowState = listenerState.withContext(newContext)
                )
                listenerRepository.update(listener, conn)
            }
        }
    }
}
