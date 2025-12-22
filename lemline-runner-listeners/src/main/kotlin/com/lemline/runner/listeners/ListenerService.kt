// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenConfig
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.CachedUntilCondition
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Service for handling listener-related workflow events.
 *
 * Provides business logic for:
 * - Starting listeners (creating listener records for CloudEvent consumption)
 * - Handling foreach iteration completion
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
     * @param instance The instance message containing the listen started event
     * @return true if the listener was created, false if it already existed (idempotent)
     */
    suspend fun handleListenStarted(instance: InstanceMessage<WorkflowEvent.ListenStarted>): Boolean {
        val state = instance.workflowState
        val config = state.config
        val listenerId = state.nodeStack.deriveIdempotentId("-listen")

        // Create listener model - workflow identity derived from instanceMessage
        val listener = ListenerModel(
            id = listenerId,
            instanceMessage = instance,
            strategy = ListenerStrategy.from(config),
            timeoutAt = config.timeoutAt,
            outboxScheduledFor = Clock.System.now(),
        )

        // Calculate correlation values from expect expressions
        listener.correlationValues = calculateCorrelationValues(config)

        // Set totalFilters (enables direct UPDATE optimization)
        listener.filtersCount = config.filters.size

        // Lookup hasForeach and until configuration from cached workflow definition
        val cachedTask = WorkflowCache.getListenTasks(instance.workflowInfo)
            .find { it.nodePosition == state.nodePosition }
        listener.hasForeach = cachedTask?.hasForeach ?: false

        // Store until configuration for expression evaluation
        listener.hasUntil = cachedTask?.until != null
        listener.untilExpression =
            (cachedTask?.until as? CachedUntilCondition.Expression)?.expression

        // Insert listener into database
        val rowsInserted = listenerRepository.insert(listener)
        if (rowsInserted == 0) {
            logger.info { "Listener $listenerId already exists (idempotent insert)" }
            return false
        }

        logger.debug {
            "Listen task started: $listenerId for workflow ${instance.workflowId} " +
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
                    val scope = JsonObject(emptyMap()).toJsonNode() as com.fasterxml.jackson.databind.node.ObjectNode
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
}
