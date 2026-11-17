// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.toJsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JQExpressionTest {

    private val emptyScope = LemlineJson.jsonObject

    @Test
    fun `root runtime scope preserves the previous singleton representation`() {
        val state = com.lemline.core.states.RootState(
            startedAt = kotlin.time.Clock.System.now(),
            workflowId = com.lemline.common.values.WorkflowId.random(),
        ).withDefinition(buildJsonObject {})
        assertEquals(parseJson("{}"), state.scope["runtime"])
    }

    @Test
    fun `finite decimal values outside double range remain numeric`() {
        val input = parseJson("1e1000")
        assertEquals(input, JQExpression.eval(input, ".", emptyScope))
    }

    @Test
    fun `non-finite results retain the previous valid JSON string representation`() {
        val result = JQExpression.eval(parseJson("{}"), "{values: [nan, infinite, -infinite]}", emptyScope)
        assertEquals(parseJson("""{"values":["NaN","Infinity","-Infinity"]}"""), result)
        assertEquals(result, parseJson(result.toString()))
    }

    @Test
    fun `updates leave the input and scope trees unchanged`() {
        val input = parseJson("""{"nested":{"value":1},"items":[1,2]}""")
        val scope = parseJson("""{"saved":{"value":2}}""").jsonObject
        val inputBefore = input.deepCopy<JsonNode>()
        val scopeBefore = scope.deepCopy()
        val result = JQExpression.eval(input, ".nested.value = 9 | .items += [3] | .saved = (\$saved | .value = 7)", scope)
        assertEquals(parseJson("""{"nested":{"value":9},"items":[1,2,3],"saved":{"value":7}}"""), result)
        assertEquals(inputBefore, input)
        assertEquals(scopeBefore, scope)
    }

    @Test
    fun `multiple emissions preserve arrays without modifying earlier results`() {
        val input = parseJson("""{"items":[1,2]}""")
        assertEquals(parseJson("[[1,2],[1,2]]"), JQExpression.eval(input, ".items, .items", emptyScope))
        assertEquals(parseJson("""{"items":[1,2]}"""), input)
    }

    @Test
    fun `test eval returns input for NullNode`() {
        val input = """{"key": "value"}""".toJsonNode()
        val filter = NullNode.instance

        val result = JQExpression.eval(input, filter, emptyScope, false)

        assertEquals(input, result)
    }

    @Test
    fun `nested literal nulls are preserved while expressions are evaluated`() {
        val input = """{"key":"value"}""".toJsonNode()
        val filter = """{"missing":null,"values":[null,{"missing":null},"${'$'}{ .key }"]}""".toJsonNode()
        val expected = """{"missing":null,"values":[null,{"missing":null},"value"]}""".toJsonNode()
        for (mode in EvaluationMode.entries) {
            assertEquals(expected, JQExpression.eval(input, filter, emptyScope, false, mode))
        }
    }

    @Test
    fun `test eval evaluates ValueNode correctly`() {
        val input = """{"key": "value"}""".toJsonNode()
        val filter = jsonValue(".key")

        assertEquals(
            jsonValue("value"),
            JQExpression.eval(input, filter, emptyScope, true),
        )

        assertEquals(
            filter,
            JQExpression.eval(input, filter, emptyScope, false),
        )

        assertEquals(
            jsonValue("value"),
            JQExpression.eval(input, jsonValue("\${.key}"), emptyScope, false),
        )
    }

    @Test
    fun `loose mode evaluates bare expressions and preserves invalid strings`() {
        val input = """{"value": 3}""".toJsonNode()
        assertEquals(
            jsonValue(3),
            JQExpression.eval(input, jsonValue(".value"), emptyScope, false, EvaluationMode.LOOSE),
        )
        assertEquals(
            jsonValue("ordinary text"),
            JQExpression.eval(input, jsonValue("ordinary text"), emptyScope, false, EvaluationMode.LOOSE),
        )
    }

    @Test
    fun `test eval processes ObjectNode correctly`() {
        val input = """{"key": "value"}""".toJsonNode()
        val filter = """{"field": ".key"}""".toJsonNode()

        val result = JQExpression.eval(input, filter, emptyScope, true)

        val expected = """{"field": "value"}""".toJsonNode()
        assertEquals(expected, result)
    }

    @Test
    fun `test eval with scope variable influences output`() {
        val input = """{"key": "value"}""".toJsonNode()

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val filter = jsonValue(".key + \$scopedKey")

        // Scope to override the value of "$scopedKey"
        val customScope = """{"scopedKey": "ScopedValue"}""".toJsonNode() as ObjectNode

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, filter, customScope, true)

        // Ensure the concat value is returned
        assertEquals(jsonValue("valueScopedValue"), result)
    }

    @Test
    fun `test eval with scope object influences output`() {
        val input = """{"key": "value"}""".toJsonNode()

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val filter = jsonValue(".key + \$scopedObject.scopedKey")

        // Scope to override the value of "$scopedKey"
        val customScope = """{"scopedObject": {"scopedKey": "ScopedValue"}}""".toJsonNode() as ObjectNode

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, filter, customScope, true)

        // Ensure the concat value is returned
        assertEquals(jsonValue("valueScopedValue"), result)
    }

    @Test
    fun `test contains function on string`() {
        // Test contains on a string input
        val input = jsonValue("https://orders.example.com/api")

        assertEquals(
            jsonValue(true),
            JQExpression.eval(input, jsonValue("contains(\"orders\")"), emptyScope, true),
        )

        assertEquals(
            jsonValue(false),
            JQExpression.eval(input, jsonValue("contains(\"users\")"), emptyScope, true),
        )
    }

    @Test
    fun `test startswith function on string`() {
        // Test startswith on a string input
        val input = jsonValue("https://orders.example.com/api")

        assertEquals(
            jsonValue(true),
            JQExpression.eval(input, jsonValue("startswith(\"https://\")"), emptyScope, true),
        )

        assertEquals(
            jsonValue(false),
            JQExpression.eval(input, jsonValue("startswith(\"http://\")"), emptyScope, true),
        )
    }

    @Test
    fun `test endswith function on string`() {
        // Test endswith on a string input
        val input = jsonValue("https://orders.example.com/api")

        assertEquals(
            jsonValue(true),
            JQExpression.eval(input, jsonValue("endswith(\"/api\")"), emptyScope, true),
        )

        assertEquals(
            jsonValue(false),
            JQExpression.eval(input, jsonValue("endswith(\"/v1\")"), emptyScope, true),
        )
    }

    @Test
    fun `test string comparison operators`() {
        // Test string comparison
        val input = jsonValue("2024-06-15")

        assertEquals(
            jsonValue(true),
            JQExpression.eval(input, jsonValue(". > \"2024-01-01\""), emptyScope, true),
        )

        assertEquals(
            jsonValue(false),
            JQExpression.eval(input, jsonValue(". < \"2024-01-01\""), emptyScope, true),
        )
    }
}
