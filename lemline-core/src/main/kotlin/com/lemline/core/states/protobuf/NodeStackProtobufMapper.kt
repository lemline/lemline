// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states.protobuf

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.errors.InternalException
import com.lemline.core.states.CallFunctionState
import com.lemline.core.states.DoState
import com.lemline.core.states.ForState
import com.lemline.core.states.ForeachState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.NodeState
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.TryState
import com.lemline.messages.internal.v1.CallFunctionStateProto
import com.lemline.messages.internal.v1.DoStateProto
import com.lemline.messages.internal.v1.ForStateProto
import com.lemline.messages.internal.v1.ForeachStateProto
import com.lemline.messages.internal.v1.InternalErrorProto
import com.lemline.messages.internal.v1.NodeStackProto
import com.lemline.messages.internal.v1.NodeStateProto
import com.lemline.messages.internal.v1.RootStateProto
import com.lemline.messages.internal.v1.StackFrameProto
import com.lemline.messages.internal.v1.TaskStateProto
import com.lemline.messages.internal.v1.TryStateProto
import java.time.Instant as JavaInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

object NodeStackProtobufMapper {

    fun toProto(nodeStack: NodeStack): NodeStackProto =
        NodeStackProto(
            frames = nodeStack.map { frame -> frame.toProto() }
        )

    fun fromProto(nodeStack: NodeStackProto): NodeStack =
        NodeStack(
            nodeStack.frames.map { frame -> frame.toDomain() }
        )

    private fun StackFrame.toProto(): StackFrameProto =
        StackFrameProto(
            position = position.toString(),
            state = state.toProto(),
            counter = counter
        )

    private fun StackFrameProto.toDomain(): StackFrame =
        StackFrame(
            position = NodePosition(position),
            state = state?.toDomain() ?: error("StackFrameMessage.state is required"),
            counter = counter
        )

    private fun NodeState.toProto(): NodeStateProto = when (this) {
        is RootState -> NodeStateProto(root = toProto())
        is DoState -> NodeStateProto(do_state = toProto())
        is ForState -> NodeStateProto(for_state = toProto())
        is ForeachState -> NodeStateProto(foreach_state = toProto())
        is CallFunctionState -> NodeStateProto(call_function_state = toProto())
        is TryState -> NodeStateProto(try_state = toProto())
        is TaskState -> NodeStateProto(task_state = toProto())
    }

    private fun NodeStateProto.toDomain(): NodeState {
        root?.let { return it.toDomain() }
        do_state?.let { return it.toDomain() }
        for_state?.let { return it.toDomain() }
        foreach_state?.let { return it.toDomain() }
        task_state?.let { return it.toDomain() }
        call_function_state?.let { return it.toDomain() }
        try_state?.let { return it.toDomain() }
        error("NodeStateMessage has no state set")
    }

    private fun RootState.toProto(): RootStateProto =
        RootStateProto(
            started_at = startedAt.toProtoInstant(),
            workflow_id = workflowId.toString(),
            workflow_input_json = workflowInput.toProtoJsonValue(),
            context_json = context.toProtoJsonStruct(),
            has_waiting_parent = hasWaitingParent
        )

    private fun RootStateProto.toDomain(): RootState =
        RootState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            workflowId = WorkflowId(IDV7.from(workflow_id)),
            workflowInput = workflow_input_json?.toKotlinJsonElement() ?: buildJsonObject { },
            context = context_json?.toKotlinJsonObject() ?: buildJsonObject { },
            hasWaitingParent = has_waiting_parent
        )

    private fun DoState.toProto(): DoStateProto =
        DoStateProto(
            started_at = startedAt.toProtoInstant(),
            index = index
        )

    private fun DoStateProto.toDomain(): DoState =
        DoState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            index = index
        )

    private fun ForState.toProto(): ForStateProto =
        ForStateProto(
            started_at = startedAt.toProtoInstant(),
            collection_json = collection.toProtoJsonListValue(),
            index = index,
            for_each = forEach,
            for_at = forAt
        )

    private fun ForStateProto.toDomain(): ForState =
        ForState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            collection = collection_json?.toKotlinJsonElementList() ?: emptyList(),
            index = index,
            forEach = for_each,
            forAt = for_at
        )

    private fun ForeachState.toProto(): ForeachStateProto =
        ForeachStateProto(
            started_at = startedAt.toProtoInstant(),
            item_json = item.toProtoJsonValue(),
            index = index,
            item_var = itemVar,
            index_var = indexVar
        )

    private fun ForeachStateProto.toDomain(): ForeachState =
        ForeachState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            item = item_json?.toKotlinJsonElement() ?: JsonNull,
            index = index,
            itemVar = item_var,
            indexVar = index_var
        )

    private fun TaskState.toProto(): TaskStateProto =
        TaskStateProto(started_at = startedAt.toProtoInstant())

    private fun TaskStateProto.toDomain(): TaskState =
        TaskState(startedAt = started_at.toKotlinInstantOrEpoch())

    private fun CallFunctionState.toProto(): CallFunctionStateProto =
        CallFunctionStateProto(
            started_at = startedAt.toProtoInstant(),
            entered = entered
        )

    private fun CallFunctionStateProto.toDomain(): CallFunctionState =
        CallFunctionState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            entered = entered
        )

    private fun TryState.toProto(): TryStateProto =
        TryStateProto(
            started_at = startedAt.toProtoInstant(),
            transformed_input_json = transformedInput.toProtoJsonValue(),
            attempt_index = attemptIndex,
            running_catch = runningCatch,
            last_error = lastError?.toProto(),
            error_as = errorAs,
            has_started = hasStarted
        )

    private fun TryStateProto.toDomain(): TryState =
        TryState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            transformedInput = transformed_input_json?.toKotlinJsonElement() ?: buildJsonObject { },
            attemptIndex = attempt_index,
            runningCatch = running_catch,
            lastError = last_error?.toDomain(),
            errorAs = error_as,
            hasStarted = has_started
        )

    private fun InternalException.Error.toProto(): InternalErrorProto =
        InternalErrorProto(
            type = type,
            status = status,
            position = position,
            title = title,
            details = details
        )

    private fun InternalErrorProto.toDomain(): InternalException.Error =
        InternalException.Error(
            type = type,
            status = status,
            position = position,
            title = title,
            details = details
        )

    private fun Instant.toProtoInstant(): JavaInstant =
        JavaInstant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

    private fun JavaInstant?.toKotlinInstantOrEpoch(): Instant =
        when (this) {
            null -> Instant.fromEpochSeconds(0)
            else -> Instant.fromEpochSeconds(epochSecond, nano.toLong())
        }
}
