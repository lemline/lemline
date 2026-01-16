// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.CallState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.CallFunctionStarted
import io.serverlessworkflow.api.types.CallFunction
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Node processor for CallFunction (function call task) - pure functional model.
 *
 * CallFunction enables workflows to invoke named functions defined in use.functions,
 * from URLs, or from resource catalogs.
 *
 * ## Example Workflow
 *
 * ```yaml
 * use:
 *   functions:
 *     validateAddress:
 *       call: expression
 *       with:
 *         code: |
 *           function validateAddress(street, city, zipCode) {
 *             return { valid: true };
 *           }
 * do:
 *   - checkAddress:
 *       call: validateAddress
 *       with:
 *         street: "${ .customer.address.street }"
 *         city: "${ .customer.address.city }"
 *         zipCode: "${ .customer.address.zip }"
 * ```
 *
 * ## Function Sources
 *
 * - **URL**: `call: https://example.com/function.yaml` - External function definition
 * - **Named**: `call: validateAddress` - Function defined in workflow's use.functions
 * - **Catalog**: `call: logMessage:1.0@globalUtils` - Function from imported catalog
 *
 * @property node Immutable CallFunction definition
 */
@ExperimentalTime
class CallFunctionProcessor(
    node: Node<CallFunction>,
) : NodeProcessor<CallFunction, CallState>(node) {

    override val isAsync = true

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope) = CallState()

    /**
     * Build the function call configuration.
     *
     * Extracts the function reference and resolves all arguments from the 'with' section,
     * evaluating any expressions against the current scope.
     *
     * @param nodeStack The stack of nodes currently being processed
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return CallFunctionStarted event with the resolved configuration
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
        logger.debug { "Building function call config for task: ${node.name}" }

        val functionRef = node.task.call

        // Resolve 'with' arguments (evaluate expressions)
        val arguments = node.task.with?.additionalProperties?.mapValues { (_, value) ->
            evaluateArgument(value, transformedInput, scope)
        } ?: emptyMap()

        val config = CallFunctionConfig(
            functionRef = functionRef,
            arguments = arguments
        )

        return CallFunctionStarted(
            nodeStack = nodeStack,
            input = transformedInput,
            config = config
        )
    }

    /**
     * Evaluates an argument value, resolving expressions if present.
     *
     * @param value The argument value (may be a runtime expression)
     * @param data The current data context
     * @param scope Expression evaluation scope
     * @return The resolved JsonElement value
     */
    private fun evaluateArgument(value: Any?, data: JsonElement, scope: Scope): JsonElement {
        val jsonValue = with(LemlineJson) { value.toJsonElement() }
        return when {
            jsonValue is JsonPrimitive && jsonValue.isString -> {
                val str = jsonValue.content
                if (ExpressionUtils.isExpr(str)) {
                    val trimmed = ExpressionUtils.trimExpr(str)
                    eval(data, JsonPrimitive(trimmed), scope, false)
                } else {
                    jsonValue
                }
            }
            else -> jsonValue
        }
    }
}
