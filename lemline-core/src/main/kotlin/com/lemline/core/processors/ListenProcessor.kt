// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.ListenState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ListenStarted
import com.lemline.core.utils.toDuration
import com.lemline.core.workflows.CachedEventConsumption
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.Timeout
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Node processor for ListenTask - pure functional model.
 *
 * ListenTask allows workflows to wait for and react to external CloudEvents,
 * enabling event-driven behavior within workflow systems.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - waitForOrder:
 *       listen:
 *         to:
 *           one:
 *             with:
 *               type: com.example.order.shipped
 *             correlate:
 *               orderId:
 *                 from: '${ .orderId }'
 *                 expect: '${ $input.orderId }'
 * ```
 *
 * ## Consumption Strategies
 *
 * - **one**: Wait for a single event matching the filter
 * - **any**: Wait for first event matching any filter (or accumulate with `until`)
 * - **all**: Wait for one event per filter
 *
 * ## Correlation
 *
 * Correlation links events to specific workflow instances:
 * - **from**: JQ expression to extract value from event data
 * - **expect**: Optional expected value from workflow context. If absent,
 *   first event sets the baseline (Mode 2 correlation)
 *
 * @property node Immutable ListenTask definition
 */
class ListenProcessor(
    node: Node<ListenTask>,
) : AbstractProcessor<ListenTask, ListenState>(node) {

    override val isAsync = true

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope) = ListenState()

    /**
     * Handles the "started" event for a listen task by creating a `ListenStarted` event
     * with the appropriate listen configuration.
     *
     * @param nodeStack The current stack of workflow nodes representing the execution state.
     * @param transformedInput The input data transformed for use in this workflow state.
     * @param scope The current scope of the workflow execution, providing environmental context.
     * @return A `WorkflowEvent` representing the starting state of this segment of the workflow.
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
        val listenConfig = node.task.listen
            ?: raiseError(CONFIGURATION, "Listen task missing 'listen' configuration")

        val listenTo = listenConfig.to
            ?: raiseError(CONFIGURATION, "Listen task missing 'listen.to' configuration")

        val consumption = CachedEventConsumption.from(listenTo.get())
        val strategy = consumption.strategy
        val filters = consumption.filters.map { it.standard }
        val until = consumption.untilExpression?.let {
            UntilCondition.Expression(if (ExpressionUtils.isExpr(it)) ExpressionUtils.trimExpr(it) else it)
        } ?: consumption.untilEvents?.let { UntilCondition.Event(it) }

        // Read mode (defaults to DATA if not specified)
        val readAs = listenConfig.read ?: ListenAndReadAs.DATA

        // Calculate timeout if specified in task base
        val timeoutAt = node.task.timeout?.let { taskTimeout ->
            when (val timeoutValue = taskTimeout.get()) {
                is Timeout -> {
                    timeoutValue.after?.let { timeoutAfter ->
                        try {
                            val duration = timeoutAfter.toDuration()
                            if (duration > Duration.ZERO) Clock.System.now() + duration else null
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse listen timeout: $timeoutAfter" }
                            null
                        }
                    }
                }

                else -> {
                    logger.warn { "Unsupported timeout type: ${timeoutValue?.javaClass?.name}" }
                    null
                }
            }
        }

        // Build correlation context from workflow scope for evaluating `correlate.expect`
        // This includes $input, $context, etc. that expect expressions can reference
        val correlationContext = buildCorrelationContext(consumption.allFilters().map { it.standard }, scope)

        val config = ListenConfig(
            strategy = strategy,
            filters = filters,
            until = until,
            readAs = readAs,
            timeoutAt = timeoutAt,
            correlationContext = correlationContext
        )

        logger.debug { "Listen config built: task=${node.name}, config=$config" }

        return ListenStarted(
            nodeStack = nodeStack,
            rawOutput = transformedInput,
            config = config,
        )
    }


    /**
     * Build the correlation context containing workflow data needed for `correlate.expect` evaluation.
     * This includes values like $input that expect expressions can reference.
     *
     * Only includes context if there are correlations with `expect` expressions.
     */
    private fun buildCorrelationContext(
        filters: List<EventFilter>,
        scope: Scope
    ): JsonElement? {
        // Check if any filter has correlations with expect expressions
        val hasExpectExpressions = filters.any { filter ->
            filter.correlations?.values?.any { it.expect != null } == true
        }

        if (!hasExpectExpressions) return null

        // Build context object with scope variables
        // Scope is a JsonObject, so we access keys directly
        return buildJsonObject {
            // Include input for $input references
            scope["input"]?.let { put("input", it) }

            // Include context for $context references
            scope["context"]?.let { put("context", it) }

            // Include workflow metadata if available
            scope["workflow"]?.let { put("workflow", it) }

            // Include task context
            scope["task"]?.let { put("task", it) }
        }
    }
}
