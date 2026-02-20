// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class WorkflowErrorTypeTest {

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

    @Test
    fun `verify error type slugs`() {
        assertEquals("configuration", WorkflowErrorType.CONFIGURATION.type)
        assertEquals("validation", WorkflowErrorType.VALIDATION.type)
        assertEquals("expression", WorkflowErrorType.EXPRESSION.type)
        assertEquals("authentication", WorkflowErrorType.AUTHENTICATION.type)
        assertEquals("authorization", WorkflowErrorType.AUTHORIZATION.type)
        assertEquals("timeout", WorkflowErrorType.TIMEOUT.type)
        assertEquals("communication", WorkflowErrorType.COMMUNICATION.type)
        assertEquals("runtime", WorkflowErrorType.RUNTIME.type)
    }
}
