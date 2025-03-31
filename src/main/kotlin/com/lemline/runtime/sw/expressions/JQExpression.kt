package com.lemline.runtime.sw.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import io.serverlessworkflow.impl.json.JsonUtils
import net.thisptr.jackson.jq.Output
import net.thisptr.jackson.jq.Versions
import net.thisptr.jackson.jq.exception.JsonQueryException
import net.thisptr.jackson.jq.internal.javacc.ExpressionParser
import net.thisptr.jackson.jq.Scope as JQScope

/**
 * Object that provides functionality to evaluate JQ expressions.
 */
object JQExpression : ExpressionEvaluator {

    // The jqVersion of JQ being used.
    private val jqVersion = Versions.JQ_1_6

    private fun ObjectNode.toJQScope() = JQScope.newEmptyScope().apply {
        fields().forEach { field -> setValue(field.key, field.value) }
    }

    /**
     * Evaluates a JQ expression against a given JSON node within a specified scope.
     *
     * @param input The JSON node to evaluate the expression against.
     * @param expr The JQ expression to evaluate.
     * @param scope The scope in which to evaluate the expression.
     * @return The result of the evaluation as a JsonNode.
     * @throws IllegalArgumentException If the expression cannot be evaluated.
     */
    override fun eval(input: JsonNode, expr: String, scope: ObjectNode): JsonNode = try {
        val output = JsonNodeOutput()
        val trimmedExpr = ExpressionUtils.trimExpr(expr)
        ExpressionParser.compile(trimmedExpr, jqVersion).apply(scope.toJQScope(), input, output)
        output.result!!
    } catch (e: JsonQueryException) {
        throw IllegalArgumentException("Unable to evaluate content $input using expr $expr", e)
    }

    /**
     * Custom Output implementation to capture the result of the JQ expression evaluation.
     */
    private class JsonNodeOutput : Output {
        // The result of the JQ expression evaluation.
        var result: JsonNode? = null

        /**
         * Captures the output of the JQ expression evaluation.
         *
         * @param out The JsonNode output to capture.
         */
        override fun emit(out: JsonNode) {
            result = when (result) {
                null -> out
                is ArrayNode -> (result as ArrayNode).add(out)
                else -> JsonUtils.mapper().createArrayNode().add(result).add(out)
            }
        }
    }


}