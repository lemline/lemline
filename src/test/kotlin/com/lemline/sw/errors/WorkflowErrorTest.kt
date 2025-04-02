package com.lemline.sw.errors

import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.sw.errors.WorkflowErrorType.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorkflowErrorTest {
    private val mapper = ObjectMapper()

    @Test
    fun `test error serialization to JSON`() {
        val error = WorkflowError(
            errorType = VALIDATION,
            title = "Invalid_input",
            status = 400,
            details = "The_input_data_does_not_match_the_required_schema",
            position = "/do/0"
        )

        val json = mapper.writeValueAsString(error)
        val expected = """
            {
                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/validation",
                "status":400,
                "position":"/do/0",
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
                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/timeout",
                "title":"Operation timed out",
                "status":408,
                "details":"The operation exceeded the maximum allowed time",
                "position":"/do/1/try"
            }
        """.trimIndent()

        val error = mapper.readValue(json, WorkflowError::class.java)

        assertEquals(TIMEOUT, error.errorType)
        assertEquals("Operation timed out", error.title)
        assertEquals(408, error.status)
        assertEquals("The operation exceeded the maximum allowed time", error.details)
        assertEquals("/do/1/try", error.position)
    }

    @Test
    fun `test error with default status serialization`() {
        val error = WorkflowError(
            errorType = RUNTIME,
            title = "Internal_error",
            details = "An_unexpected_error_occurred",
            position = "/do/2",
            status = 455
        )

        val json = mapper.writeValueAsString(error)
        val expected = """
            {
                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
                "status":455,
                "position":"/do/2",
                "title":"Internal_error",
                "details":"An_unexpected_error_occurred"
            }
        """.trimIndent().replace("\\s".toRegex(), "")

        assertEquals(expected, json)
    }

}