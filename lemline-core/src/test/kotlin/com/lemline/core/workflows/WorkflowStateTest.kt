// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@ExperimentalTime
class WorkflowStateTest {

    @Test
    fun `WorkflowState deserializes to JSON correctly`() {
        repeat(50) {
            // Given
            val workflowState = WorkflowState.random()

            // When
            val serialized = workflowState.toJsonString()
            val deserialized = WorkflowState.fromJsonString(serialized)

            // Then
            assertEquals(workflowState, deserialized)
        }
    }

    @Test
    fun `WorkflowState uses shortened property names in serialization`() {
        // Given
        val workflowState = WorkflowState.random()

        // When
        val serialized = workflowState.toJsonString()
        val jsonElement = Json.parseToJsonElement(serialized).jsonObject

        // Then - Verify shortened names are used
        assertEquals("p", jsonElement.keys.find { it == "p" }, "Should use 'p' for currentPosition")
        assertEquals("s", jsonElement.keys.find { it == "s" }, "Should use 's' for currentStates")

        // Verify full names are NOT used
        assertTrue(!jsonElement.containsKey("currentPosition"), "Should not contain 'currentPosition'")
        assertTrue(!jsonElement.containsKey("currentStates"), "Should not contain 'currentStates'")
    }
}
