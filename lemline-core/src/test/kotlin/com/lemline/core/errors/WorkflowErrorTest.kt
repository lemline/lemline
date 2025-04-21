// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.JsonPointer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorkflowErrorTest {
    @Test
    fun `test error serialization to JSON`() {
        val error = WorkflowError(
            errorType = WorkflowErrorType.VALIDATION,
            title = "Invalid_input",
            status = 400,
            details = "The_input_data_does_not_match_the_required_schema",
            position = JsonPointer("/do/0").toPosition(),
        )

        val json = LemlineJson.encodeToString(error)
        val expected = """
            {
                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/validation",
                "status":400,
                "instance":"/do/0",
                "title":"Invalid_input",
                "details":"The_input_data_does_not_match_the_required_schema"
            }
        """.trimIndent().replace("\\s".toRegex(), "")

        assertEquals(expected, json)
    }

    @Test
    fun `test error deserialization from JSON`() {
        val json = """
            {
                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/validation",
                "status":408,
                "instance":"/do/1/try",
                "title":"Operation timed out",
                "details":"The operation exceeded the maximum allowed time"
            }
        """.trimIndent()

        val error: WorkflowError = LemlineJson.decodeFromString(json)

        assertEquals("https://serverlessworkflow.io/spec/1.0.0/errors/validation", error.type)
        assertEquals("Operation timed out", error.title)
        assertEquals(408, error.status)
        assertEquals("The operation exceeded the maximum allowed time", error.details)
        assertEquals("/do/1/try", error.instance)
    }

    @Test
    fun `test error with default status serialization`() {
        val error = WorkflowError(
            errorType = WorkflowErrorType.RUNTIME,
            title = "Internal_error",
            details = "An_unexpected_error_occurred",
            position = JsonPointer("/do/2").toPosition(),
        )

        val json = LemlineJson.encodeToString(error)
        val expected = """
            {
                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
                "status":500,
                "instance":"/do/2",
                "title":"Internal_error",
                "details":"An_unexpected_error_occurred"
            }
        """.trimIndent().replace("\\s".toRegex(), "")

        assertEquals(expected, json)
    }
}
