package com.lemline.sw.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.Json
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import kotlinx.serialization.json.*

internal interface ExpressionEvaluator {
    fun eval(input: JsonNode, expr: String, scope: ObjectNode): JsonNode

    /**
     * Convert JsonElement to JsonNode and JsonObject to ObjectNode then evaluate the expression.
     */
    fun eval(input: JsonElement, expr: String, scope: JsonObject): JsonElement = with(Json) {
        eval(input.toJsonNode(), expr, scope.toJsonNode() as ObjectNode).toJsonElement()
    }

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
