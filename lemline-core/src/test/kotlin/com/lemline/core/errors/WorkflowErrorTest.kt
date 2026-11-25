// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class WorkflowErrorTest {
    @Test
    fun `test error serialization to JSON`() {
        val error = InternalException.Error(
            errorType = WorkflowErrorType.VALIDATION,
            title = "Invalid_input",
            status = 400,
            detail = "The_input_data_does_not_match_the_required_schema",
            position = NodePosition("/do/0"),
        )

        val json = LemlineJson.encodeToString(error)
        val expected = """
            {
                "type":"https://open-workflow-specification.org/spec/1.0.0/errors/validation",
                "status":400,
                "instance":"/do/0",
                "title":"Invalid_input",
                "detail":"The_input_data_does_not_match_the_required_schema"
            }
        """.trimIndent().replace("\\s".toRegex(), "")

        assertEquals(expected, json)
    }

    @Test
    fun `test error deserialization from JSON`() {
        val json = """
            {
                "type":"https://open-workflow-specification.org/spec/1.0.0/errors/validation",
                "status":408,
                "instance":"/do/1/try",
                "title":"Operation timed out",
                "detail":"The operation exceeded the maximum allowed time"
            }
        """.trimIndent()

        val error: InternalException.Error = LemlineJson.decodeFromString(json)

        assertEquals("https://open-workflow-specification.org/spec/1.0.0/errors/validation", error.type)
        assertEquals("Operation timed out", error.title)
        assertEquals(408, error.status)
        assertEquals("The operation exceeded the maximum allowed time", error.detail)
        assertEquals("/do/1/try", error.position)
    }

    @Test
    fun `test error with default status serialization`() {
        val error = InternalException.Error(
            errorType = WorkflowErrorType.RUNTIME,
            title = "Internal_error",
            detail = "An_unexpected_error_occurred",
            position = NodePosition("/do/2"),
        )

        val json = LemlineJson.encodeToString(error)
        val expected = """
            {
                "type":"https://open-workflow-specification.org/spec/1.0.0/errors/runtime",
                "status":500,
                "instance":"/do/2",
                "title":"Internal_error",
                "detail":"An_unexpected_error_occurred"
            }
        """.trimIndent().replace("\\s".toRegex(), "")

        assertEquals(expected, json)
    }

    @Test
    fun `a communication failure becomes a communication error carrying its status`() {
        val error = InternalException.Error.from(CommunicationException("HTTP 503: busy", 503), NodePosition("/do/0"))

        assertEquals("https://open-workflow-specification.org/spec/1.0.0/errors/communication", error.type)
        assertEquals(503, error.status)
        assertEquals("HTTP 503: busy", error.title)
        assertEquals("/do/0", error.position)
    }

    @Test
    fun `a communication failure without response uses the default communication status`() {
        val error = InternalException.Error.from(CommunicationException("Unable to reach api.example.com"), NodePosition("/do/0"))

        assertEquals("https://open-workflow-specification.org/spec/1.0.0/errors/communication", error.type)
        assertEquals(500, error.status)
    }

    @Test
    fun `any other failure stays a runtime error`() {
        val error = InternalException.Error.from(IllegalStateException("boom"), NodePosition("/do/0"))

        assertEquals("https://open-workflow-specification.org/spec/1.0.0/errors/runtime", error.type)
        assertEquals(500, error.status)
    }
}
