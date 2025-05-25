// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.core.json.LemlineJson
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal interface ExpressionEvaluator {
    fun eval(input: JsonNode, expr: String, scope: ObjectNode): JsonNode

    /**
     * Convert JsonElement to JsonNode and JsonObject to ObjectNode then evaluate the expression.
     */
    fun eval(input: JsonElement, expr: String, scope: JsonObject): JsonElement = with(LemlineJson) {
        eval(input.toJsonNode(), ExpressionUtils.trimExpr(expr), scope.toJsonNode() as ObjectNode).toJsonElement()
    }

    /**
     * Evaluates an expression against a given JSON element within a specified scope.
     *
     * @param data The JSON element to evaluate the expression against.
     * @param expr The JQ expression to evaluate.
     * @param scope The scope in which to evaluate the expression.
     * @param force If true, forces evaluation of the expression even if not surrounded by `${}`.
     * @return The result of the evaluation as a JsonElement.
     */
    fun eval(data: JsonElement, expr: JsonElement, scope: JsonObject, force: Boolean): JsonElement = when (expr) {
        is JsonNull -> data

        is JsonPrimitive -> when (expr.isString && (force || ExpressionUtils.isExpr(expr.content))) {
            true -> eval(data, expr.content, scope)
            false -> expr
        }

        is JsonObject -> JsonObject(expr.mapValues { (_, value) -> eval(data, value, scope, force) })

        is JsonArray -> JsonArray(expr.map { value -> eval(data, value, scope, force) })
    }
}
