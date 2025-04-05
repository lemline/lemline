package com.lemline.sw.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.serverlessworkflow.impl.expressions.ExpressionUtils

internal interface ExpressionEvaluator {
    fun eval(input: JsonNode, expr: String, scope: ObjectNode): JsonNode

    fun eval(data: JsonNode, expr: JsonNode, scope: ObjectNode, force: Boolean): JsonNode = when (expr) {
        is NullNode -> data
        is TextNode -> when (force || ExpressionUtils.isExpr(expr.asText())) {
            true -> eval(data, expr.asText(), scope)
            false -> expr
        }

        is ObjectNode -> expr.apply {
            fields().forEach { it.setValue(eval(data, it.value, scope, force)) }
        }

        else -> expr
    }
}