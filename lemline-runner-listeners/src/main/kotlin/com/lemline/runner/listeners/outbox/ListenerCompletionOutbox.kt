// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners.outbox

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.core.expressions.JQExpression
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import com.lemline.runner.listeners.ListenerConfig
import com.lemline.runner.listeners.ListenerEventRepository
import com.lemline.runner.listeners.ListenerModel
import com.lemline.runner.listeners.ListenerRepository
import com.lemline.runner.listeners.CloudEventService
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Outbox processor for listener completions.
 *
 * ## Simplified Architecture
 *
 * This outbox handles the final step: completing the listen task after
 * all events have been processed (including foreach iterations if applicable).
 *
 * Listeners are picked up when `outbox_delayed_until` is set (ready for processing).
 *
 * Note: `completed_at` being set only means the listener stopped collecting events.
 * The outbox waits for `outbox_delayed_until` which is set after all foreach processing
 * completes (or immediately for non-foreach listeners).
 *
 * ## How it works
 *
 * Before each batch, this outbox calls `batchMarkReady()` to:
 * 1. Check completion criteria for each strategy
 * 2. For non-foreach: Set both `completed_at` and `outbox_delayed_until`
 * 3. For foreach: Set `completed_at` only; `outbox_delayed_until` set later after foreach completes
 *
 * Then it processes ready listeners:
 * 1. Aggregates foreach outputs (if applicable) from listener_events
 * 2. Sends `ResumeWithCompletedTask` command with the aggregated output
 * 3. Marks listener for cleanup
 *
 * ## Completion Criteria
 *
 * | Strategy | Completion Condition |
 * |----------|---------------------|
 * | ONE/ANY | One event with `outbox_completed_at IS NOT NULL` |
 * | ALL | All filters matched (`COUNT(DISTINCT filter_index) >= filters_count`) |
 * | ANY_UNTIL_EXPR | Until expression evaluates to true (handled by ListenerRepository) |
 * | ANY_UNTIL_EVENT | Termination event received (handled by CloudEventHandler) |
 *
 * @see AbstractOutbox for base outbox pattern implementation
 * @see ListenerForeachOutbox for foreach.do processing
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ListenerCompletionOutbox : AbstractOutbox<ListenerModel>() {

    override val jobName = "Listeners outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var listenerFeatureConfig: ListenerConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    override val outboxRepository: WithOutboxRepository<ListenerModel> get() = listenerRepository

    override val crudRepository: WithCrudRepository<ListenerModel> get() = listenerRepository

    /** Is this outbox enabled? */
    override val enabled by lazy { listenerFeatureConfig.enabled }

    /** Outbox processing configuration - fallback to wait config if listener config not set */
    override val outboxConfig: OutboxConfig? by lazy { listenerFeatureConfig.outbox }

    /**
     * Override doWork to check completion criteria before processing.
     *
     * This calls `batchMarkReady()` to mark eligible listeners as ready,
     * evaluates until expressions for ANY_UNTIL_EXPR listeners,
     * checks terminated ANY_UNTIL_EVENT listeners,
     * then delegates to the standard outbox processing.
     */
    override suspend fun doWork() {
        // Step 1: Mark eligible ONE/ANY/ALL listeners as completed
        val markedCompleted = listenerRepository.batchMarkReady()
        if (markedCompleted > 0) {
            logger.debug { "Marked $markedCompleted listeners as completed" }
        }

        // Step 2: Evaluate until expressions for ANY_UNTIL_EXPR listeners
        val markedCompletedByExpr = evaluateUntilExpressions()
        if (markedCompletedByExpr > 0) {
            logger.debug { "Marked $markedCompletedByExpr ANY+until(expr) listeners as completed via expression evaluation" }
        }

        // Step 3: Check terminated ANY_UNTIL_EVENT listeners whose foreach processing is now complete
        val markedCompletedByTermination = listenerRepository.batchMarkCompletedTerminatedListeners()
        if (markedCompletedByTermination > 0) {
            logger.debug { "Marked $markedCompletedByTermination ANY+until(event) listeners as ready for processing (foreach complete)" }
        }

        // Step 4: Process ready listeners via standard outbox flow
        super.doWork()
    }

    /**
     * Evaluates until expressions for ANY_UNTIL_EXPR listeners with completed events.
     *
     * This is called from doWork() to check if any listeners have accumulated
     * enough completed events to satisfy their until condition. This is essential
     * for foreach processing because:
     * - CloudEvent arrival only stores events, doesn't complete them
     * - Foreach.do processing completes events asynchronously
     * - Until expressions should only be evaluated against completed events
     *
     * @return Number of listeners marked as completed
     */
    private suspend fun evaluateUntilExpressions(): Int {
        val listenersWithEvents = listenerRepository.findPendingUntilExprListeners()
        if (listenersWithEvents.isEmpty()) return 0

        var totalMarkedCompleted = 0

        for ((listener, accumulatedEvents) in listenersWithEvents) {
            val untilExpr = listener.untilExpression ?: continue

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
                val marked = listenerRepository.markListenerCompleted(listener.id)
                if (marked > 0) {
                    totalMarkedCompleted++
                    logger.debug { "ANY+until expression evaluated to true for listener ${listener.id}" }
                }
            }
        }

        return totalMarkedCompleted
    }

    /**
     * Evaluates an until expression against accumulated events.
     * The expression should return a boolean.
     */
    private fun evaluateUntilExpression(expression: String, events: JsonArray): Boolean = try {
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

    /**
     * Process a single listener completion.
     * Aggregates foreach outputs and sends the resume command.
     *
     * Applies the `readAs` transformation to stored CloudEvents based on the
     * workflow definition configuration.
     */
    override suspend fun process(entity: ListenerModel) {

        val events = listenerEventRepository.getCompletedEvents(entity.id)

        // Apply readAs transformation to each stored CloudEvent
        val outputArray = JsonArray(events.map {
            entity.applyReadAs(Json.parseToJsonElement(it) as JsonObject)
        })

        // Create completion command
        val resumeCommand = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = entity.instanceMessage.workflowState.nodeStack,
            rawOutput = outputArray
        )

        val resumeMessage = InstanceMessage(
            workflowInfo = entity.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        // Derive idempotent message ID from listener ID
        val messageId = entity.id.derive("-listen-complete")

        commandEmitter.send(resumeMessage, messageId)

        logger.info { "Listen completion sent for listener ${entity.id}" }

        // Mark the listener to be cleaned up
        entity.cleanupAfter = Clock.System.now()
    }

    /**
     * Extracts the data portion from a stored CloudEvent JSON for until expression evaluation.
     * Until expressions operate on the event data, not the full envelope.
     */
    private fun extractDataFromStoredEvent(eventJson: String): JsonElement =
        CloudEventService.extractData(eventJson)
}
