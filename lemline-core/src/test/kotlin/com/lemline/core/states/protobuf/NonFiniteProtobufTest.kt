// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.fasterxml.jackson.databind.node.DoubleNode
import com.lemline.common.json.parseJson
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.messages.internal.v1.WorkflowStateProto
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class NonFiniteProtobufTest {
    @Test
    fun `binary nodes use the existing base64 string representation on the wire`() {
        val state = StepByStepOrchestrator.initCmd(workflowInput = com.fasterxml.jackson.databind.node.BinaryNode.valueOf(byteArrayOf(1, 2, 3)))
        val proto = WorkflowStateProtobufMapper.toProto(state)
        val decoded = WorkflowStateProtobufMapper.fromProto(proto) as WorkflowCommand.ResumeFromTask
        assertEquals("AQID", decoded.rawInput.textValue())
    }

    @Test
    fun `non-finite protobuf numbers retain their numeric wire representation`() {
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val state = StepByStepOrchestrator.initCmd(workflowInput = DoubleNode.valueOf(number))
            val bytes = WorkflowStateProtobufMapper.toProto(state).encode()
            val decoded = WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(bytes)) as WorkflowCommand.ResumeFromTask
            assertTrue(decoded.rawInput.isNumber)
            if (number.isNaN()) assertTrue(decoded.rawInput.doubleValue().isNaN())
            else assertEquals(number, decoded.rawInput.doubleValue())
            assertContentEquals(bytes, WorkflowStateProtobufMapper.toProto(decoded).encode())
        }
    }

    @Test
    fun `finite runtime numbers overflowing protobuf doubles still decode`() {
        val state = StepByStepOrchestrator.initCmd(workflowInput = parseJson("1e1000"))
        val proto = WorkflowStateProtobufMapper.toProto(state)
        val decoded = WorkflowStateProtobufMapper.fromProto(proto) as WorkflowCommand.ResumeFromTask
        assertEquals(Double.POSITIVE_INFINITY, decoded.rawInput.doubleValue())
    }
}
