package com.lemline.swruntime.sw.expressions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JQExpressionTest {

    private val scope = JsonNodeFactory.instance.objectNode()

    @Test
    fun `test eval returns rawInput for NullNode`() {
        val rawInput = JsonNodeFactory.instance.objectNode().put("key", "value")
        val fromNode = NullNode.getInstance()

        val result = JQExpression.eval(rawInput, fromNode, scope)

        assertEquals(rawInput, result)
    }

    @Test
    fun `test eval evaluates TextNode correctly`() {
        val rawInput = JsonNodeFactory.instance.objectNode().put("key", "value")
        val fromNode = TextNode(".key")

        val expected = JsonNodeFactory.instance.textNode("value")
        val result = JQExpression.eval(rawInput, fromNode, scope)

        assertEquals(expected, result)
    }

    @Test
    fun `test eval processes ObjectNode correctly`() {
        val rawInput = JsonNodeFactory.instance.objectNode().put("key", "value")
        val fromNode = JsonNodeFactory.instance.objectNode().apply {
            put("field", ".key")
        }

        val expected = JsonNodeFactory.instance.objectNode().apply { put("field", "value") }
        val result = JQExpression.eval(rawInput, fromNode, scope)

        assertEquals(expected, result)
    }

    @Test
    fun `test eval throws exception for unsupported node type`() {
        val rawInput = JsonNodeFactory.instance.objectNode().put("key", "value")
        val unsupportedNode = BooleanNode.valueOf(false)

        val exception = assertThrows<IllegalArgumentException> {
            JQExpression.eval(rawInput, unsupportedNode, scope)
        }

        assertEquals("Unsupported JSON node: $unsupportedNode", exception.message)
    }

    @Test
    fun `test eval with scope variable influences output`() {
        val input: JsonNode = JsonNodeFactory.instance.objectNode().apply {
            put("key", "value")
        }

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val expression = ".key + \$scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = JsonNodeFactory.instance.objectNode().apply {
            set<TextNode>("scopedKey", JsonNodeFactory.instance.textNode("ScopedValue"))
        }

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, expression, customScope)

        // Ensure the concat value is returned
        assertEquals(JsonNodeFactory.instance.textNode("valueScopedValue"), result)
    }

    @Test
    fun `test eval with scope object influences output`() {
        val input: JsonNode = JsonNodeFactory.instance.objectNode().apply {
            put("key", "value")
        }

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val expression = ".key + \$scopedObject.scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = JsonNodeFactory.instance.objectNode().apply {
            set<ObjectNode>("scopedObject", JsonNodeFactory.instance.objectNode().apply {
                put("scopedKey", "ScopedValue")
            })
        }

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, expression, customScope)

        // Ensure the concat value is returned
        assertEquals(JsonNodeFactory.instance.textNode("valueScopedValue"), result)
    }
}