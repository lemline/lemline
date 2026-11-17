// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
import io.serverlessworkflow.impl.expressions.ExpressionUtils

internal interface ExpressionEvaluator {
    fun eval(input: JsonNode, expr: String, scope: ObjectNode): JsonNode

    /**
     * Evaluates an expression against a given JSON element within a specified scope.
     *
     * @param data The JSON element to evaluate the expression against.
     * @param expr The JQ expression to evaluate.
     * @param scope The scope in which to evaluate the expression.
     * @param force If true, forces evaluation of the expression even if not surrounded by `${}`.
     * @return The result of the evaluation as a JsonNode.
     */
    fun eval(
        data: JsonNode,
        expr: JsonNode,
        scope: ObjectNode,
        force: Boolean,
        mode: EvaluationMode = EvaluationMode.STRICT,
    ): JsonNode = if (expr is NullNode) data else evalValue(data, expr, scope, force, mode)

    // Only an absent top-level expression means identity. Nulls inside literal
    // objects and arrays are data and must survive recursive evaluation.
    private fun evalValue(
        data: JsonNode,
        expr: JsonNode,
        scope: ObjectNode,
        force: Boolean,
        mode: EvaluationMode,
    ): JsonNode = when (expr) {
        is NullNode -> NullNode.instance

        is ValueNode -> when (expr.isString && (force || mode == EvaluationMode.LOOSE || ExpressionUtils.isExpr(expr.content))) {
            true -> try {
                eval(data, ExpressionUtils.trimExpr(expr.content), scope)
            } catch (e: Exception) {
                if (mode == EvaluationMode.LOOSE) expr else throw e
            }
            false -> expr
        }

        is ObjectNode -> ObjectNode(expr.mapValues { (_, value) -> evalValue(data, value, scope, force, mode) })

        is ArrayNode -> ArrayNode(expr.map { value -> evalValue(data, value, scope, force, mode) })
        else -> error("Unsupported JSON node: ${expr.nodeType}")
    }
}
