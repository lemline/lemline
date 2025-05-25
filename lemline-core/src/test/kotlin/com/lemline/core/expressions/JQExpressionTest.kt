// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.expressions

import com.lemline.core.json.LemlineJson
import com.lemline.core.json.toJsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JQExpressionTest {

    private val emptyScope = LemlineJson.jsonObject

    @Test
    fun `test eval returns input for NullNode`() {
        val input = """{"key": "value"}""".toJsonElement()
        val filter = JsonNull

        val result = JQExpression.eval(input, filter, emptyScope, false)

        assertEquals(input, result)
    }

    @Test
    fun `test eval evaluates JsonPrimitive correctly`() {
        val input = """{"key": "value"}""".toJsonElement()
        val filter = JsonPrimitive(".key")

        assertEquals(
            JsonPrimitive("value"),
            JQExpression.eval(input, filter, emptyScope, true),
        )

        assertEquals(
            filter,
            JQExpression.eval(input, filter, emptyScope, false),
        )

        assertEquals(
            JsonPrimitive("value"),
            JQExpression.eval(input, JsonPrimitive("\${.key}"), emptyScope, false),
        )
    }

    @Test
    fun `test eval processes ObjectNode correctly`() {
        val input = """{"key": "value"}""".toJsonElement()
        val filter = """{"field": ".key"}""".toJsonElement()

        val result = JQExpression.eval(input, filter, emptyScope, true)

        val expected = """{"field": "value"}""".toJsonElement()
        assertEquals(expected, result)
    }

    @Test
    fun `test eval with scope variable influences output`() {
        val input = """{"key": "value"}""".toJsonElement()

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val filter = ".key + \$scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = """{"scopedKey": "ScopedValue"}""".toJsonElement() as JsonObject

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, filter, customScope)

        // Ensure the concat value is returned
        assertEquals(JsonPrimitive("valueScopedValue"), result)
    }

    @Test
    fun `test eval with scope object influences output`() {
        val input = """{"key": "value"}""".toJsonElement()

        // JQ expression to extract and concatenate the value of "key" with a scoped variable "scopedKey"
        val filter = ".key + \$scopedObject.scopedKey"

        // Scope to override the value of "$scopedKey"
        val customScope = """{"scopedObject": {"scopedKey": "ScopedValue"}}""".toJsonElement() as JsonObject

        // Use the custom scope in the evaluation
        val result = JQExpression.eval(input, filter, customScope)

        // Ensure the concat value is returned
        assertEquals(JsonPrimitive("valueScopedValue"), result)
    }
}
