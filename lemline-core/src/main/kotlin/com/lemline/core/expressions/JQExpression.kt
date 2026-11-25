// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.normalizeRuntimeTree
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.Output
import net.thisptr.jackson.jq.Scope as JQScope
import net.thisptr.jackson.jq.Versions
import net.thisptr.jackson.jq.exception.JsonQueryException
import net.thisptr.jackson.jq.internal.javacc.ExpressionParser

/**
 * Object that provides functionality to evaluate JQ expressions.
 */
object JQExpression : ExpressionEvaluator {

    // The jqVersion of JQ being used.
    private val jqVersion = Versions.JQ_1_6

    // Root scope with built-in functions loaded (shared across threads in read-only manner)
    private val rootScope: JQScope by lazy {
        JQScope.newEmptyScope().also { scope ->
            BuiltinFunctionLoader.getInstance().loadFunctions(jqVersion, scope)
            // jackson-jq 1.2.0 omits jq's todate alias.
            if (scope.getFunction("todate", 0) == null) {
                scope.getFunction("todateiso8601", 0)?.let { scope.addFunction("todate", 0, it) }
            }
        }
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
        ExpressionParser.compile(expr, jqVersion).apply(scope.toJQScope(), input.deepCopy(), output)
        output.result!!.normalizeRuntimeTree()
    } catch (e: JsonQueryException) {
        throw IllegalArgumentException("Unable to evaluate content $input using expr $expr", e)
    }

    /**
     * Evaluates a JQ expression, returning null on any error instead of throwing.
     *
     * Useful for conditional expressions where type mismatches or missing fields
     * should result in a falsy value rather than propagating an exception.
     * For example, `.source | contains("roof")` returns null if `.source` is missing.
     *
     * @param input The JSON node to evaluate the expression against.
     * @param expr The JQ expression to evaluate.
     * @param scope The scope in which to evaluate the expression.
     * @return The result of the evaluation as a JsonNode, or null on any error.
     */
    fun evalOrNull(input: JsonNode, expr: String, scope: ObjectNode): JsonNode? = try {
        val output = JsonNodeOutput()
        ExpressionParser.compile(expr, jqVersion).apply(scope.toJQScope(), input.deepCopy(), output)
        output.result?.normalizeRuntimeTree()
    } catch (_: JsonQueryException) {
        null
    }

    /**
     * Custom Output implementation to capture the result of the JQ expression evaluation.
     */
    private class JsonNodeOutput : Output {
        private val mapper = LemlineJson.jacksonMapper

        // The result of the JQ expression evaluation.
        var result: JsonNode? = null
        private var multiple = false

        /**
         * Captures the output of the JQ expression evaluation.
         *
         * @param out The JsonNode output to capture.
         */
        override fun emit(out: JsonNode) {
            result = when (result) {
                null -> out
                else -> if (multiple) (result as ArrayNode).add(out) else {
                    multiple = true
                    mapper.createArrayNode().add(result).add(out)
                }
            }
        }
    }

    /**
     * Converts an object JSON to a JQScope with built-in functions available.
     * Creates a child scope from rootScope to allow setting variables without
     * modifying the shared root scope.
     */
    private fun ObjectNode.toJQScope(): JQScope = JQScope.newChildScope(rootScope).apply {
        // Expressions can update values. Never expose cached definitions or another
        // branch's state to the evaluator as mutable nodes.
        properties().forEach { field -> setValue(field.key, field.value.deepCopy<JsonNode>()) }
    }
}
