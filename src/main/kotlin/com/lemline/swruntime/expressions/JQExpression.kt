package com.lemline.swruntime.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.serverlessworkflow.api.types.ExportAs
import io.serverlessworkflow.api.types.InputFrom
import io.serverlessworkflow.api.types.OutputAs
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
object JQExpression {

    // The jqVersion of JQ being used.
    private val jqVersion = Versions.JQ_1_6

    private fun ObjectNode.toJQScope() = JQScope.newEmptyScope().apply {
        fields().forEach { field -> setValue(field.key, field.value) }
    }

    @JvmStatic
    fun eval(input: JsonNode, expr: String, scope: ObjectNode) =
        eval(input, expr, scope.toJQScope())

    @JvmStatic
    fun eval(input: JsonNode, expr: JsonNode, scope: ObjectNode) =
        eval(input, expr, scope.toJQScope())

    @JvmStatic
    fun eval(input: JsonNode, inputFrom: InputFrom?, scope: ObjectNode): JsonNode =
        inputFrom?.let { eval(input, JsonUtils.fromValue(it.get()), scope) } ?: input


    @JvmStatic
    fun eval(input: JsonNode, outputAs: OutputAs?, scope: ObjectNode): JsonNode =
        outputAs?.let { eval(input, JsonUtils.fromValue(it.get()), scope) } ?: input

    @JvmStatic
    fun eval(input: JsonNode, exportAs: ExportAs, scope: ObjectNode): JsonNode =
        eval(input, JsonUtils.fromValue(exportAs.get()), scope)

    /**
     * Evaluates a JQ expression against a given JSON node within a specified scope.
     *
     * @param input The JSON node to evaluate the expression against.
     * @param expr The JQ expression to evaluate.
     * @param scope The scope in which to evaluate the expression.
     * @return The result of the evaluation as a JsonNode.
     * @throws IllegalArgumentException If the expression cannot be evaluated.
     */
    @JvmStatic
    internal fun eval(input: JsonNode, expr: String, scope: JQScope): JsonNode = try {
        val output = JsonNodeOutput()
        val trimmedExpr = ExpressionUtils.trimExpr(expr)
        ExpressionParser.compile(trimmedExpr, jqVersion).apply(scope, input, output)
        output.result!!
    } catch (e: JsonQueryException) {
        throw IllegalArgumentException("Unable to evaluate content $input using expr $expr", e)
    }

    /**
     * Evaluates the instanceRawInput from a given JsonNode.
     * (This is syntaxic sugar to more easily returns an object as described by expr)
     *
     * @param input the instanceRawInput JsonNode
     * @param expr the JsonNode to evaluate from
     * @param scope the evaluation scope
     * @return the evaluated JsonNode
     * @throws IllegalArgumentException if the JsonNode type is unsupported
     */
    @JvmStatic
    internal fun eval(input: JsonNode, expr: JsonNode, scope: JQScope): JsonNode = when (expr) {
        is NullNode -> input
        is TextNode -> eval(input, expr.asText(), scope)
        is ObjectNode -> expr.also {
            expr.fields().forEach { field ->
                field.setValue(eval(input, field.value, scope))
            }
        }

        else -> throw IllegalArgumentException("Unsupported JSON node: $expr")
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