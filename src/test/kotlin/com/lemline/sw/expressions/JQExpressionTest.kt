package com.lemline.sw.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private val jsonFactory = JsonNodeFactory.instance

class JQExpressionTest {

    private val scope = jsonFactory.objectNode()

    @Test
    fun `test eval returns rawInput for NullNode`() {
        val rawInput = jsonFactory.objectNode().put("key", "value")
        val fromNode = NullNode.getInstance()

        val result = JQExpression.eval(rawInput, fromNode, scope, false)

        assertEquals(rawInput, result)
    }

    @Test
    fun `test eval evaluates TextNode correctly`() {
        val rawInput = jsonFactory.objectNode().put("key", "value")
        val fromNode = TextNode(".key")

        assertEquals(
            jsonFactory.textNode("value"),
            JQExpression.eval(rawInput, fromNode, scope, true)
        )

        assertEquals(
            fromNode,
            JQExpression.eval(rawInput, fromNode, scope, false)
        )

        assertEquals(
            jsonFactory.textNode("value"),
            JQExpression.eval(rawInput, TextNode("\${.key}"), scope, false)
        )
    }

    @Test
    fun `test eval processes ObjectNode correctly`() {
        val rawInput = jsonFactory.objectNode().put("key", "value")
        val fromNode = jsonFactory.objectNode().put("field", ".key")

        val expected = jsonFactory.objectNode().put("field", "value")
        val result = JQExpression.eval(rawInput, fromNode, scope, true)

        assertEquals(expected, result)
    }

    @Test
    fun `test eval throws exception for unsupported node type`() {
        val rawInput = jsonFactory.objectNode().put("key", "value")
        val unsupportedNode = BooleanNode.valueOf(false)

        val exception = assertThrows<IllegalArgumentException> {
            JQExpression.eval(rawInput, unsupportedNode, scope, true)
        }

        assertEquals("Unsupported JSON node: $unsupportedNode", exception.message)
    }

    @Test
    fun `test eval with scope variable influences output`() {
        val input: JsonNode = jsonFactory.objectNode().put("key", "value")

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val expression = ".key + \$scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = jsonFactory.objectNode().apply {
            set<TextNode>("scopedKey", jsonFactory.textNode("ScopedValue"))
        }

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, expression, customScope)

        // Ensure the concat value is returned
        assertEquals(jsonFactory.textNode("valueScopedValue"), result)
    }

    @Test
    fun `test eval with scope object influences output`() {
        val input: JsonNode = jsonFactory.objectNode().put("key", "value")

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val expression = ".key + \$scopedObject.scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = jsonFactory.objectNode().apply {
            set<ObjectNode>("scopedObject", jsonFactory.objectNode().put("scopedKey", "ScopedValue"))
        }

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, expression, customScope)

        // Ensure the concat value is returned
        assertEquals(jsonFactory.textNode("valueScopedValue"), result)
    }
}