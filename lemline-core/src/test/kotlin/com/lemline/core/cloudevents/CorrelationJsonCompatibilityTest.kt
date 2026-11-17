// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.common.json.parseJson
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class CorrelationJsonCompatibilityTest {
    @Test
    fun `overflowing legacy values stay numeric until correlation evaluation completes`() {
        val input = parseJson("""{"key":1e1000}""")
        assertEquals("number", CloudEventMatcher.evaluateFromExpression(".key | type", input))
        assertEquals("true", CloudEventMatcher.evaluateFromExpression(".key | isinfinite", input))
        assertEquals("Infinity", CloudEventMatcher.evaluateFromExpression(".key", input))
        assertEquals("[\"Infinity\"]", CloudEventMatcher.evaluateFromExpression("[.key]", input))
    }

    @Test
    fun `structured keys retain historical control-character escaping`() {
        val input = parseJson("""{"nested":["\u001b",10000000.5]}""")
        assertEquals("""["\u001b",1.00000005E7]""", CloudEventMatcher.evaluateFromExpression(".nested", input))
    }

    @Test
    fun `numeric keys retain pre-migration formatting including tostring and structured results`() {
        val input = parseJson("""{"id":10000000.5,"text":"10000000.5","nested":[10000000.5]}""")
        assertEquals("1.00000005E7", CloudEventMatcher.evaluateFromExpression(".id", input))
        assertEquals("1.00000005e+7", CloudEventMatcher.evaluateFromExpression(".id | tostring", input))
        assertEquals("[1.00000005E7]", CloudEventMatcher.evaluateFromExpression(".nested", input))
        assertEquals("10000000.5", CloudEventMatcher.evaluateFromExpression(".text", input))
    }
}
