package com.lemline.swruntime.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

internal interface ExpressionEvaluator {
    fun eval(input: JsonNode, expr: String, scope: ObjectNode): JsonNode

    fun eval(data: JsonNode, expr: JsonNode, scope: ObjectNode): JsonNode = when (expr) {
        is NullNode -> data
        is TextNode -> eval(data, expr.asText(), scope)
        is ObjectNode -> expr.apply {
            fields().forEach { it.setValue(eval(data, it.value, scope)) }
        }

        else -> throw IllegalArgumentException("Unsupported JSON node: $expr")
    }
}