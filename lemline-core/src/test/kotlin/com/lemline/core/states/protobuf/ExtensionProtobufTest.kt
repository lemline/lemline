// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.fasterxml.jackson.databind.node.NullNode
import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.*
import com.lemline.core.tasks.FlowDirectiveEnum
import com.lemline.messages.internal.v1.*
import kotlin.test.*
import kotlin.time.Instant
import org.junit.jupiter.api.Test

class ExtensionProtobufTest {
    private val position = NodePosition("/do/0/owner")
    private val started = Instant.fromEpochSeconds(100)
    private fun stack(phase: ExtensionPhase): NodeStack {
        val pending = phase == ExtensionPhase.BEFORE || phase == ExtensionPhase.READY
        val result = if (phase == ExtensionPhase.AFTER || phase == ExtensionPhase.READY) PreparedTaskResult(NullNode.instance, NullNode.instance, FlowDirectiveEnum.Exit) else null
        return StepByStepOrchestrator.initCmd().nodeStack
            .push(position, if (pending) PendingTaskState(started) else TaskState(started))
            .withExtension(ExtensionExecutionState(listOf(0), phase, if (phase == ExtensionPhase.BEFORE || phase == ExtensionPhase.AFTER) 0 else null, NullNode.instance, NullNode.instance, result))
    }

    @Test fun `all phases round trip preserving null and active zero`() {
        for (phase in ExtensionPhase.entries) {
            val stack = stack(phase)
            val proto = NodeStackProtobufMapper.toProto(stack)
            assertNotNull(proto.frames.last().extension!!.raw_input_json)
            assertEquals(stack, NodeStackProtobufMapper.fromProto(NodeStackProto.ADAPTER.decode(proto.encode())))
        }
    }

    @Test fun `extension return survives scheduling conversion and both message mappings`() {
        for (kind in ExtensionReturnKind.entries) {
            val event = WorkflowEvent.TaskScheduled(stack(ExtensionPhase.BEFORE), position, NullNode.instance, null,
                ExtensionReturn(NodePosition("$position/_ext/0/before/do"), kind))
            val decoded = WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(WorkflowStateProtobufMapper.toProto(event).encode())) as WorkflowEvent.TaskScheduled
            assertEquals(event.extensionReturn, decoded.resume().extensionReturn)
            val command = decoded.resume()
            assertEquals(command, WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(WorkflowStateProtobufMapper.toProto(command).encode())))
        }
    }

    @Test fun `missing fields and invalid phases are rejected while old frames remain valid`() {
        val valid = NodeStackProtobufMapper.toProto(stack(ExtensionPhase.BEFORE))
        val frame = valid.frames.last()
        val extension = frame.extension!!
        val invalid = listOf(
            frame.copy(extension = null),
            frame.copy(extension = extension.copy(phase = ExtensionPhaseProto.EXTENSION_PHASE_PROTO_UNSPECIFIED)),
            frame.copy(extension = extension.copy(active_extension_index = null)),
            frame.copy(extension = extension.copy(raw_input_json = null)),
            frame.copy(extension = extension.copy(transformed_input_json = null)),
            frame.copy(extension = extension.copy(phase = ExtensionPhaseProto.EXTENSION_PHASE_PROTO_BODY, active_extension_index = null)),
        )
        for (bad in invalid) assertFails { NodeStackProtobufMapper.fromProto(valid.copy(frames = valid.frames.dropLast(1) + bad)) }
        val legacy = StepByStepOrchestrator.initCmd().nodeStack.push(position, TaskState(started))
        assertEquals(legacy, NodeStackProtobufMapper.fromProto(NodeStackProto.ADAPTER.decode(NodeStackProtobufMapper.toProto(legacy).encode())))
    }

    @Test fun `canonical ProtoJSON fixture preserves null values and zero index`() {
        // Produced by buf convert --type lemline.internal.v1.ExtensionExecutionStateProto from:
        // {"selectedExtensionIndices":[0],"phase":"EXTENSION_PHASE_PROTO_AFTER","activeExtensionIndex":0,
        //  "rawInputJson":{"value":null},"transformedInputJson":{"value":{"data":[null,0,false,""]}},
        //  "preparedResult":{"rawOutputJson":{"value":null},"transformedOutputJson":{"value":null}}}
        val bytes = java.util.Base64.getDecoder().decode("CgEAEAMYACIECgIIAConCiUqIwohCgRkYXRhEhkyFwoCCAAKCREAAAAAAAAAAAoCIAAKAhoAMgwKBAoCCAASBAoCCAA=")
        val decoded = ExtensionProtobufMapper.fromProto(ExtensionExecutionStateProto.ADAPTER.decode(bytes))
        assertEquals(0, decoded.activeExtensionIndex)
        assertEquals(NullNode.instance, decoded.rawInput)
        assertEquals(parseJson("""{"data":[null,0,false,""]}"""), decoded.transformedInput)
        assertEquals(NullNode.instance, decoded.preparedResult!!.rawOutput)
        assertEquals(decoded, ExtensionProtobufMapper.fromProto(ExtensionExecutionStateProto.ADAPTER.decode(ExtensionProtobufMapper.toProto(decoded).encode())))
    }
}
