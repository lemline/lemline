package com.lemline.core.states

import com.lemline.core.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

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
}
