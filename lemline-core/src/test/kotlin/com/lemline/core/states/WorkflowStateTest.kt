package com.lemline.core.states

import com.lemline.common.json.LemlineJson
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
            val serialized = LemlineJson.encodeToString(workflowCommand)
            val deserialized = LemlineJson.decodeFromString<WorkflowState>(serialized)

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
            val serialized = LemlineJson.encodeToString(workflowEvent)
            val deserialized = LemlineJson.decodeFromString<WorkflowState>(serialized)

            // Then
            assertEquals(workflowEvent, deserialized)
        }
    }

}
