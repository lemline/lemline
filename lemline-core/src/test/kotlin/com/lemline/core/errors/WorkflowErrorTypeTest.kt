// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.core.json.LemlineJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WorkflowErrorTypeTest {

    @Test
    fun `test serialization to JSON`() {
        // Test all error types
        WorkflowErrorType.entries.forEach { errorType ->
            val json = LemlineJson.encodeToString(errorType)
            assertEquals("\"${errorType.type}\"", json)
        }
    }

    @Test
    fun `test deserialization from JSON`() {
        // Test all error types
        WorkflowErrorType.entries.forEach { expectedType ->
            val json = "\"${expectedType.type}\""
            val deserializedType = LemlineJson.decodeFromString<WorkflowErrorType>(json)
            assertEquals(expectedType, deserializedType)
        }
    }

    @Test
    fun `test specific error type serialization`() {
        val error = WorkflowErrorType.VALIDATION
        val json = LemlineJson.encodeToString(error)
        assertEquals("\"validation\"", json)
    }

    @Test
    fun `test specific error type deserialization`() {
        val json = "\"timeout\""
        val error = LemlineJson.decodeFromString<WorkflowErrorType>(json)
        assertEquals(WorkflowErrorType.TIMEOUT, error)
    }

    @Test
    fun `verify default status codes`() {
        assertEquals(400, WorkflowErrorType.CONFIGURATION.defaultStatus)
        assertEquals(400, WorkflowErrorType.VALIDATION.defaultStatus)
        assertEquals(400, WorkflowErrorType.EXPRESSION.defaultStatus)
        assertEquals(401, WorkflowErrorType.AUTHENTICATION.defaultStatus)
        assertEquals(403, WorkflowErrorType.AUTHORIZATION.defaultStatus)
        assertEquals(408, WorkflowErrorType.TIMEOUT.defaultStatus)
        assertEquals(500, WorkflowErrorType.COMMUNICATION.defaultStatus)
        assertEquals(500, WorkflowErrorType.RUNTIME.defaultStatus)
    }
}
