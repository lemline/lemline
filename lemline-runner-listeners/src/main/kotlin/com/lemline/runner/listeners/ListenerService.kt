// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.cloudevents.CloudEventMatcher
import com.lemline.core.functions.FunctionResolver
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.UntilCondition
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.WorkflowCache.getWorkflow
import com.lemline.core.workflows.getNode
import com.lemline.runner.common.messaging.InstanceMessage
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

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

    @Inject
    lateinit var functionResolver: FunctionResolver

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

        // The events worker may differ from the worker that executed the function.
        instanceMessage.workflowInfo.getWorkflow()?.let { workflow ->
            workflow.getNode(listenStarted.nodePosition) { ref -> functionResolver.forWorkflow(workflow)(ref) }
        }
        listener.filterPosition = WorkflowCache.listenPosition(instanceMessage.workflowInfo, listenStarted.nodePosition)
        val listenTask = WorkflowCache.getListenTask(instanceMessage.workflowInfo, listenStarted.nodePosition)
        listener.hasForeach = listenTask?.hasForeach ?: false

        // Store until configuration for expression evaluation
        listener.hasUntil = config.until != null

        // Is until an expression?
        listener.untilExpression = (config.until as? UntilCondition.Expression)?.expression

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
    internal fun calculateCorrelationValues(config: ListenConfig): String? {
        val correlationContext = config.correlationContext ?: return null
        if (correlationContext !is ObjectNode) return null

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

                val result = CloudEventMatcher.evaluateExpect(expectExpr, correlationContext)

                val value = result.toCorrelationValue()

                if (value != null) {
                    evaluatedValues[key] = value
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate correlation expect expression '$expectExpr' for key '$key'" }
            }
        }

        if (evaluatedValues.isEmpty()) return null

        // Both expected and incoming keys must use the historical string-map codec.
        return LemlineJson.encodeToString(evaluatedValues.toSortedMap().toMap())
    }
}

internal fun NodeStack.listenerId(): IDV7 = deriveIdempotentId("-listen")
internal fun NodeStack.previousListenerId(): IDV7 = decrementTopCounter().deriveIdempotentId("-listen")
