package com.lemline.core.states

import com.lemline.core.random.random
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import org.junit.jupiter.api.Test

@ExperimentalTime
class WorkflowStateTest {

    // ========================================
    // General Serialization/Deserialization Tests
    // ========================================

    @Test
    fun `should serialize and deserialize all WorkflowCommand variants`() {
        repeat(50) {
            // Given
            val workflowCommand = WorkflowCommand.random()

            // When
            val serialized = workflowCommand.toJsonString()
            val deserialized = WorkflowCommand.fromJsonString(serialized)

            // Then
            assertEquals(workflowCommand, deserialized)
        }
    }

    @Test
    fun `should serialize and deserialize all WorkflowEvent variants`() {
        repeat(50) {
            // Given
            val workflowEvent = WorkflowEvent.random()

            // When
            val serialized = workflowEvent.toJsonString()
            val deserialized = WorkflowEvent.fromJsonString(serialized)

            // Then
            assertEquals(workflowEvent, deserialized)
        }
    }

    @Test
    fun `should serialize and deserialize all WorkflowState variants`() {
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
