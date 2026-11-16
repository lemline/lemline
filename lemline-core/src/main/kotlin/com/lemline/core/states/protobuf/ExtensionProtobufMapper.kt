// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.lemline.common.values.NodePosition
import com.lemline.core.states.ExtensionExecutionState
import com.lemline.core.states.ExtensionPhase
import com.lemline.core.states.ExtensionReturn
import com.lemline.core.states.ExtensionReturnKind
import com.lemline.core.states.PreparedTaskResult
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper.toDomain
import com.lemline.core.states.protobuf.WorkflowStateProtobufMapper.toProto
import com.lemline.messages.internal.v1.ExtensionExecutionStateProto
import com.lemline.messages.internal.v1.ExtensionJsonValueProto
import com.lemline.messages.internal.v1.ExtensionPhaseProto
import com.lemline.messages.internal.v1.ExtensionReturnKindProto
import com.lemline.messages.internal.v1.ExtensionReturnProto
import com.lemline.messages.internal.v1.PreparedTaskResultProto

internal object ExtensionProtobufMapper {
    fun toProto(state: ExtensionExecutionState) = ExtensionExecutionStateProto(
        selected_extension_indices = state.selectedExtensionIndices,
        phase = when (state.phase) {
            ExtensionPhase.BEFORE -> ExtensionPhaseProto.EXTENSION_PHASE_PROTO_BEFORE
            ExtensionPhase.BODY -> ExtensionPhaseProto.EXTENSION_PHASE_PROTO_BODY
            ExtensionPhase.AFTER -> ExtensionPhaseProto.EXTENSION_PHASE_PROTO_AFTER
            ExtensionPhase.READY -> ExtensionPhaseProto.EXTENSION_PHASE_PROTO_READY
        },
        active_extension_index = state.activeExtensionIndex,
        raw_input_json = ExtensionJsonValueProto(state.rawInput.toProtoJsonValue()),
        transformed_input_json = ExtensionJsonValueProto(state.transformedInput.toProtoJsonValue()),
        prepared_result = state.preparedResult?.let {
            PreparedTaskResultProto(
                raw_output_json = ExtensionJsonValueProto(it.rawOutput.toProtoJsonValue()),
                transformed_output_json = ExtensionJsonValueProto(it.transformedOutput.toProtoJsonValue()),
                pending_flow_directive = it.pendingFlowDirective?.toProto(),
            )
        },
    )

    fun fromProto(state: ExtensionExecutionStateProto) = ExtensionExecutionState(
        selectedExtensionIndices = state.selected_extension_indices,
        phase = when (state.phase) {
            ExtensionPhaseProto.EXTENSION_PHASE_PROTO_BEFORE -> ExtensionPhase.BEFORE
            ExtensionPhaseProto.EXTENSION_PHASE_PROTO_BODY -> ExtensionPhase.BODY
            ExtensionPhaseProto.EXTENSION_PHASE_PROTO_AFTER -> ExtensionPhase.AFTER
            ExtensionPhaseProto.EXTENSION_PHASE_PROTO_READY -> ExtensionPhase.READY
            ExtensionPhaseProto.EXTENSION_PHASE_PROTO_UNSPECIFIED, is ExtensionPhaseProto.Unrecognized -> error("Invalid extension phase: ${state.phase}")
        },
        activeExtensionIndex = state.active_extension_index,
        rawInput = requireNotNull(state.raw_input_json) { "Extension raw input is required" }.value_.toKotlinJsonElement(),
        transformedInput = requireNotNull(state.transformed_input_json) { "Extension transformed input is required" }.value_.toKotlinJsonElement(),
        preparedResult = state.prepared_result?.let {
            PreparedTaskResult(
                requireNotNull(it.raw_output_json) { "Extension raw output is required" }.value_.toKotlinJsonElement(),
                requireNotNull(it.transformed_output_json) { "Extension transformed output is required" }.value_.toKotlinJsonElement(),
                it.pending_flow_directive?.toDomain(),
            )
        },
    )

    fun toProto(value: ExtensionReturn) = ExtensionReturnProto(
        block_position = value.blockPosition.toString(),
        kind = when (value.kind) {
            ExtensionReturnKind.COMPLETE -> ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_COMPLETE
            ExtensionReturnKind.EXIT -> ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_EXIT
            ExtensionReturnKind.END -> ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_END
        },
    )

    fun fromProto(value: ExtensionReturnProto): ExtensionReturn {
        require(value.block_position.startsWith("/")) { "Extension block position is required" }
        return ExtensionReturn(NodePosition(value.block_position), when (value.kind) {
            ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_COMPLETE -> ExtensionReturnKind.COMPLETE
            ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_EXIT -> ExtensionReturnKind.EXIT
            ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_END -> ExtensionReturnKind.END
            ExtensionReturnKindProto.EXTENSION_RETURN_KIND_PROTO_UNSPECIFIED, is ExtensionReturnKindProto.Unrecognized -> error("Invalid extension return kind: ${value.kind}")
        })
    }
}
