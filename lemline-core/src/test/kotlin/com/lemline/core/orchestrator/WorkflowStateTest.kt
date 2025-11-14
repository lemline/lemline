// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

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
    fun `should serialize and deserialize all WorkflowState variants`() {
        repeat(50) {
            // Given
            val workflowState = WorkflowState.random()

            // When
            val serialized = LemlineJson.encodeToString(workflowState)
            val deserialized = LemlineJson.decodeFromString<WorkflowState>(serialized)

            // Then
            assertEquals(workflowState, deserialized)
        }
    }

    @Test
    fun `should preserve sealed class type information`() {
        // Given
        val states = listOf(
            WorkflowState.Completed.random(),
            WorkflowState.Failed.random(),
            WorkflowState.ReadyForNextTask.random(),
            WorkflowState.Waiting.random(),
            WorkflowState.WaitingToRetry.random(),
            WorkflowState.RunningChildWorkflow.random()
        )

        // When & Then
        states.forEach { state ->
            val serialized = LemlineJson.encodeToString(state)
            val deserialized = LemlineJson.decodeFromString<WorkflowState>(serialized)

            // Verify type is preserved
            assertEquals(state::class, deserialized::class)
        }
    }
}
