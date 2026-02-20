// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.random.randomWorkflowCommand
import com.lemline.core.random.randomWorkflowEvent
import com.lemline.core.random.randomWorkflowState
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper
import com.lemline.messages.internal.v1.WorkflowStateProto
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
            val workflowCommand = randomWorkflowCommand()

            // When
            val deserialized = roundTripViaProtobuf(workflowCommand)

            // Then
            assertEquals(workflowCommand, deserialized)
        }
    }

    @Test
    fun `should serialize and deserialize all WorkflowEvent variants`() {
        repeat(50) {
            // Given
            val workflowEvent = randomWorkflowEvent()

            // When
            val deserialized = roundTripViaProtobuf(workflowEvent)

            // Then
            assertEquals(workflowEvent, deserialized)
        }
    }

    @Test
    fun `should serialize and deserialize all WorkflowState variants`() {
        repeat(50) {
            // Given
            val workflowState = randomWorkflowState()

            // When
            val deserialized = roundTripViaProtobuf(workflowState)

            // Then
            assertEquals(workflowState, deserialized)
        }
    }

    private fun roundTripViaProtobuf(state: WorkflowState): WorkflowState {
        val proto = WorkflowStateProtobufMapper.toProto(state)
        val decoded = WorkflowStateProto.ADAPTER.decode(WorkflowStateProto.ADAPTER.encode(proto))
        return WorkflowStateProtobufMapper.fromProto(decoded)
    }

}
