// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.EmitState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.EmitStarted
import io.serverlessworkflow.api.types.EmitTask
import io.serverlessworkflow.api.types.EventData
import io.serverlessworkflow.api.types.EventDataschema
import io.serverlessworkflow.api.types.EventSource
import io.serverlessworkflow.api.types.EventTime
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import java.time.OffsetDateTime
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Node processor for EmitTask (CloudEvents emission) - pure functional model.
 *
 * EmitTask allows workflows to publish CloudEvents to external messaging systems,
 * facilitating event-driven communication between services.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - emitOrderPlaced:
 *       emit:
 *         event:
 *           with:
 *             source: https://petstore.com
 *             type: com.petstore.order.placed.v1
 *             data:
 *               orderId: ${ .orderId }
 *               customer: ${ .customer }
 * ```
 *
 * ## CloudEvents Properties
 *
 * The `emit.event.with` object contains CloudEvents properties:
 * - **source** (required): URI identifying the event context
 * - **type** (required): Event type description
 * - **id**: Unique event identifier (auto-generated if not provided)
 * - **time**: Event timestamp (defaults to current time)
 * - **subject**: Event subject in producer context
 * - **datacontenttype**: Content type (defaults to application/json)
 * - **dataschema**: URI of the schema that data adheres to
 * - **data**: Event payload
 *
 * All properties support runtime expressions using `${ expr }` syntax.
 *
 * @property node Immutable EmitTask definition
 */
class EmitProcessor(
    node: Node<EmitTask>,
) : NodeProcessor<EmitTask, EmitState>(node) {

    override val isAsync = true

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope) = EmitState()

    /**
     * Handles the event triggered when a workflow emit task is started.
     *
     * This method creates and returns the corresponding `WorkflowEvent` by resolving the properties
     * of the emit task to construct an EmitConfig. The actual CloudEvent is built by the
     * ActivityExecutor using the CloudEvents SDK.
     *
     * @param nodeStack The stack of nodes currently being processed for this workflow execution.
     * @param transformedInput The transformed JSON input that serves as the context or data for the event.
     * @param scope The scope of the current workflow execution, used for resolving runtime expressions.
     * @return A `WorkflowEvent` that represents the starting event of a workflow emit task.
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
        logger.debug { "Building emit config for task: ${node.name}" }

        val eventProps = node.task.emit?.event?.with
            ?: raiseError(CONFIGURATION, "Emit task missing 'emit.event.with' configuration")

        // Resolve all CloudEvent properties
        val source = resolveSource(eventProps.source, transformedInput, scope)
        val type = resolveType(eventProps.type, transformedInput, scope)
        val id = eventProps.id ?: UUID.randomUUID().toString()
        val time = resolveTime(eventProps.time, transformedInput, scope)
            ?: OffsetDateTime.now().toString()
        val subject = resolveString(eventProps.subject, transformedInput, scope)
        val dataschema = resolveDataschema(eventProps.dataschema, transformedInput, scope)
        val datacontenttype = eventProps.datacontenttype ?: "application/json"
        val data = resolveData(eventProps.data, transformedInput, scope)

        // Resolve extension attributes
        val extensions = eventProps.additionalProperties
            ?.mapValues { (_, value) -> value?.toString() ?: "" }
            ?.filterValues { it.isNotEmpty() }

        val config = EmitConfig(
            id = id,
            source = source,
            type = type,
            time = time,
            subject = subject,
            dataschema = dataschema,
            datacontenttype = datacontenttype,
            data = data,
            extensions = extensions
        )

        return EmitStarted(
            nodeStack = nodeStack,
            input = transformedInput,
            config = config,
        )
    }

    /**
     * Resolve the source property (required).
     * Can be a URI template or a runtime expression.
     */
    private fun resolveSource(
        source: EventSource?,
        data: JsonElement,
        scope: Scope
    ): String {
        if (source == null) {
            raiseError(CONFIGURATION, "CloudEvent 'source' is required")
        }

        return when (val value = source.get()) {
            is UriTemplate -> resolveUriTemplate(value, data, scope)
            is String -> evaluateExpression(value, data, scope)
            else -> raiseError(RUNTIME, "Unsupported EventSource type: ${value?.javaClass?.name}")
        }
    }

    /**
     * Resolve the type property (required).
     * Can be a literal string or a runtime expression.
     */
    private fun resolveType(
        type: String?,
        data: JsonElement,
        scope: Scope
    ): String {
        if (type == null) {
            raiseError(CONFIGURATION, "CloudEvent 'type' is required")
        }

        return if (ExpressionUtils.isExpr(type)) {
            evaluateExpression(type, data, scope)
        } else {
            type
        }
    }

    /**
     * Resolve the time property (optional).
     * Can be a datetime descriptor or a runtime expression.
     */
    private fun resolveTime(
        time: EventTime?,
        data: JsonElement,
        scope: Scope
    ): String? {
        if (time == null) return null

        return when (val value = time.get()) {
            is OffsetDateTime -> value.toString()
            is String -> evaluateExpression(value, data, scope)
            else -> raiseError(RUNTIME, "Unsupported EventTime type: ${value?.javaClass?.name}")
        }
    }

    /**
     * Resolve a simple string property that may be an expression.
     */
    private fun resolveString(
        value: String?,
        data: JsonElement,
        scope: Scope
    ): String? {
        if (value == null) return null

        return if (ExpressionUtils.isExpr(value)) {
            evaluateExpression(value, data, scope)
        } else {
            value
        }
    }

    /**
     * Resolve the dataschema property (optional).
     * Can be a URI or a runtime expression.
     */
    private fun resolveDataschema(
        dataschema: EventDataschema?,
        data: JsonElement,
        scope: Scope
    ): String? {
        if (dataschema == null) return null

        return when (val value = dataschema.get()) {
            is URI -> value.toString()
            is String -> evaluateExpression(value, data, scope)
            else -> raiseError(RUNTIME, "Unsupported EventDataschema type: ${value?.javaClass?.name}")
        }
    }

    /**
     * Resolve the data property (optional).
     * Can be an object or a runtime expression.
     */
    private fun resolveData(
        eventData: EventData?,
        data: JsonElement,
        scope: Scope
    ): JsonElement? {
        if (eventData == null) return null

        return when (val value = eventData.get()) {
            is String -> {
                // Runtime expression - evaluate it
                if (ExpressionUtils.isExpr(value)) {
                    val trimmed = ExpressionUtils.trimExpr(value)
                    eval(data, JsonPrimitive(trimmed), scope, false)
                } else {
                    JsonPrimitive(value)
                }
            }

            else -> {
                // Object - convert to JsonElement
                with(LemlineJson) { value.toJsonElement() }
            }
        }
    }

    /**
     * Resolve a URI template to a string.
     */
    private fun resolveUriTemplate(
        template: UriTemplate,
        data: JsonElement,
        scope: Scope
    ): String = when (val value = template.get()) {
        is URI -> value.toString()
        is String -> evaluateExpression(value, data, scope)
        else -> raiseError(RUNTIME, "Unsupported UriTemplate type: ${value?.javaClass?.name}")
    }

    /**
     * Evaluate a runtime expression if it contains JQ syntax.
     */
    private fun evaluateExpression(
        expression: String,
        data: JsonElement,
        scope: Scope
    ): String = if (ExpressionUtils.isExpr(expression)) {
        val trimmed = ExpressionUtils.trimExpr(expression)
        when (val result = eval(data, JsonPrimitive(trimmed), scope, false)) {
            is JsonPrimitive -> result.content
            else -> raiseError(RUNTIME, "Expression must evaluate to a string, but got: $result")
        }
    } else {
        expression
    }
}
