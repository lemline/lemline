// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.expressions.JQExpression
import com.lemline.core.processors.ListenConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.CachedUntilCondition
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.messaging.InstanceMessage
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Service for handling listener lifecycle operations.
 *
 * Provides business logic for:
 * - Starting listeners (creating listener records for CloudEvent consumption)
 *
 * For CloudEvent processing and event completion, see [ListenerEventService].
 */
@ApplicationScoped
class ListenerService {

    @Inject
    lateinit var listenerRepository: ListenerRepository

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
    suspend fun handleListenStarted(instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>) {
        val listenStarted = instanceMessage.workflowState
        val config = listenStarted.config

        // Idempotent creation of listener model
        val listener = ListenerModel(instanceMessage = instanceMessage)

        // Calculate correlation values from expect expressions
        listener.correlationValues = calculateCorrelationValues(config)

        // Set totalFilters (enables direct UPDATE optimization)
        listener.filtersCount = config.filters.size

        // Lookup hasForeach and until configuration from cached workflow definition
        // For function listen tasks, use suffix matching since the cached position (/_fn/do/0/task)
        // differs from the runtime position (/do/0/call/_fn/do/0/task)
        val listenTask = WorkflowCache.getListenTasks(instanceMessage.workflowInfo).find {
            if (it.isFromFunction) {
                // Suffix match: /do/0/call/_fn/do/0/task ends with /_fn/do/0/task
                listenStarted.nodePosition.toString().endsWith(it.nodePosition.toString())
            } else {
                it.nodePosition == listenStarted.nodePosition
            }
        }
        listener.hasForeach = listenTask?.hasForeach ?: false

        // Store until configuration for expression evaluation
        listener.hasUntil = listenTask?.until != null

        // Is until an expression?
        listener.untilExpression = (listenTask?.until as? CachedUntilCondition.Expression)?.expression

        // Insert listener into database
        val rowsInserted = listenerRepository.insert(listener)

        if (rowsInserted == 0) {
            logger.warn { "Listener already exists (idempotent insert): $listener" }
            return
        }

        logger.debug { "Listen task started at position ${listenStarted.nodePosition}: $listener " }
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

        val evaluatedValues = mutableMapOf<String, String>()
        for ((key, expectExpr) in expectExpressions) {
            try {
                if (!ExpressionUtils.isExpr(expectExpr)) {
                    evaluatedValues[key] = expectExpr
                    continue
                }

                val trimmedExpr = ExpressionUtils.trimExpr(expectExpr)

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
}

internal fun NodeStack.listenerId(): IDV7 = deriveIdempotentId("-listen")
internal fun NodeStack.previousListenerId(): IDV7 = decrementTopCounter().deriveIdempotentId("-listen")
