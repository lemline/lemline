package com.lemline.swruntime.errors

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorkflowErrorTypeTest {
    private val mapper = ObjectMapper()

    @Test
    fun `test serialization to JSON`() {
        // Test all error types
        WorkflowErrorType.entries.forEach { errorType ->
            val json = mapper.writeValueAsString(errorType)
            assertEquals("\"${errorType.type}\"", json)
        }
    }

    @Test
    fun `test deserialization from JSON`() {
        // Test all error types
        WorkflowErrorType.entries.forEach { expectedType ->
            val json = "\"${expectedType.type}\""
            val deserializedType = mapper.readValue(json, WorkflowErrorType::class.java)
            assertEquals(expectedType, deserializedType)
        }
    }

    @Test
    fun `test specific error type serialization`() {
        val error = WorkflowErrorType.VALIDATION
        val json = mapper.writeValueAsString(error)
        assertEquals("\"validation\"", json)
    }

    @Test
    fun `test specific error type deserialization`() {
        val json = "\"timeout\""
        val error = mapper.readValue(json, WorkflowErrorType::class.java)
        assertEquals(WorkflowErrorType.TIMEOUT, error)
    }

    @Test
    fun `verify default status codes`() {
        assertEquals(400, WorkflowErrorType.VALIDATION.defaultStatus)
        assertEquals(401, WorkflowErrorType.AUTHENTICATION.defaultStatus)
        assertEquals(403, WorkflowErrorType.AUTHORIZATION.defaultStatus)
        assertEquals(404, WorkflowErrorType.NOT_FOUND.defaultStatus)
        assertEquals(408, WorkflowErrorType.TIMEOUT.defaultStatus)
        assertEquals(500, WorkflowErrorType.COMMUNICATION.defaultStatus)
        assertEquals(400, WorkflowErrorType.EXPRESSION.defaultStatus)
        assertEquals(500, WorkflowErrorType.INTERNAL.defaultStatus)
    }
}