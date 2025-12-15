// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.schemas

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class SchemaValidatorTest {

    private val mapper = ObjectMapper()

    @Test
    fun `test validate simple required field - valid`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "required": ["name"],
                "properties": {
                    "name": { "type": "string" }
                }
            }
            """.trimIndent()
        )
        val data = mapper.readTree("""{"name": "test"}""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test validate simple required field - missing field`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "required": ["name"],
                "properties": {
                    "name": { "type": "string" }
                }
            }
            """.trimIndent()
        )
        val data = mapper.readTree("""{"other": "value"}""")

        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test validate type mismatch`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "properties": {
                    "count": { "type": "integer" }
                }
            }
            """.trimIndent()
        )
        val data = mapper.readTree("""{"count": "not a number"}""")

        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with date-time format - valid date-time`() {
        // This tests the oneOf validation bug fix
        // Schema exactly matching Serverless Workflow time field schema
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""2024-01-15T10:30:00Z"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with date-time format - valid runtime expression`() {
        // This tests the oneOf validation bug fix
        // The buggy validator would fail because date-time format fails first
        // and oneOf doesn't correctly try the pattern alternative
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""${"$"}{.timestamp}"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with date-time format - invalid value`() {
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""not-a-date-or-expression"""")

        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with complex runtime expression`() {
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""${"$"}{ . > \"2024-01-01T00:00:00Z\" }"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with contains runtime expression`() {
        // Test the exact expression used in ListenTestCases
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""${"$"}{ contains(\"2024\") }"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test nested object validation`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "properties": {
                    "user": {
                        "type": "object",
                        "required": ["id"],
                        "properties": {
                            "id": { "type": "integer" },
                            "name": { "type": "string" }
                        }
                    }
                }
            }
            """.trimIndent()
        )
        val validData = mapper.readTree("""{"user": {"id": 123, "name": "test"}}""")
        val invalidData = mapper.readTree("""{"user": {"name": "test"}}""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(validData, schema)
        }
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(invalidData, schema)
        }
    }

    @Test
    fun `test array validation`() {
        val schema = mapper.readTree(
            """
            {
                "type": "array",
                "items": { "type": "string" }
            }
            """.trimIndent()
        )
        val validData = mapper.readTree("""["a", "b", "c"]""")
        val invalidData = mapper.readTree("""["a", 123, "c"]""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(validData, schema)
        }
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(invalidData, schema)
        }
    }

    @Test
    fun `test format date-time should reject non-datetime strings`() {
        // Verify that format:date-time properly rejects non-datetime strings
        val schema = mapper.readTree(
            """
            {
                "type": "string",
                "format": "date-time"
            }
            """.trimIndent()
        )

        // Valid date-time should pass
        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(mapper.readTree(""""2024-01-15T10:30:00Z""""), schema)
        }

        // Invalid date-time should fail
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(mapper.readTree(""""not-a-date""""), schema)
        }

        // Runtime expression should fail format:date-time validation
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(mapper.readTree(""""${"$"}{ contains(\"2024\") }""""), schema)
        }
    }
}
