// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.lemline.core.json.LemlineJson
import com.lemline.core.set
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JQExpressionTest {

    private val scope = LemlineJson.jsonObject

    @Test
    fun `test eval returns rawInput for NullNode`() {
        val rawInput = LemlineJson.jsonObject.set("key", "value")
        val fromNode = JsonNull

        val result = JQExpression.eval(rawInput, fromNode, scope, false)

        assertEquals(rawInput, result)
    }

    @Test
    fun `test eval evaluates JsonPrimitive correctly`() {
        val rawInput = LemlineJson.jsonObject.set("key", "value")
        val fromNode = JsonPrimitive(".key")

        assertEquals(
            JsonPrimitive("value"),
            JQExpression.eval(rawInput, fromNode, scope, true),
        )

        assertEquals(
            fromNode,
            JQExpression.eval(rawInput, fromNode, scope, false),
        )

        assertEquals(
            JsonPrimitive("value"),
            JQExpression.eval(rawInput, JsonPrimitive("\${.key}"), scope, false),
        )
    }

    @Test
    fun `test eval processes ObjectNode correctly`() {
        val rawInput = LemlineJson.jsonObject.set("key", "value")
        val fromNode = LemlineJson.jsonObject.set("field", ".key")

        val expected = LemlineJson.jsonObject.set("field", "value")
        val result = JQExpression.eval(rawInput, fromNode, scope, true)

        assertEquals(expected, result)
    }

    @Test
    fun `test eval with scope variable influences output`() {
        val input = LemlineJson.jsonObject.set("key", "value")

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val expression = ".key + \$scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = LemlineJson.jsonObject.set("scopedKey", JsonPrimitive("ScopedValue"))

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, expression, customScope)

        // Ensure the concat value is returned
        assertEquals(JsonPrimitive("valueScopedValue"), result)
    }

    @Test
    fun `test eval with scope object influences output`() {
        val input = LemlineJson.jsonObject.set("key", "value")

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val expression = ".key + \$scopedObject.scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope =
            LemlineJson.jsonObject.set("scopedObject", LemlineJson.jsonObject.set("scopedKey", "ScopedValue"))

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, expression, customScope)

        // Ensure the concat value is returned
        assertEquals(JsonPrimitive("valueScopedValue"), result)
    }
}
