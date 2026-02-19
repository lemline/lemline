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
import com.lemline.messages.internal.v1.CallFunctionStateMessage
import com.lemline.messages.internal.v1.DoStateMessage
import com.lemline.messages.internal.v1.ForStateMessage
import com.lemline.messages.internal.v1.ForeachStateMessage
import com.lemline.messages.internal.v1.InternalErrorMessage
import com.lemline.messages.internal.v1.NodeStackMessage
import com.lemline.messages.internal.v1.NodeStateMessage
import com.lemline.messages.internal.v1.RootStateMessage
import com.lemline.messages.internal.v1.StackFrameMessage
import com.lemline.messages.internal.v1.TaskStateMessage
import com.lemline.messages.internal.v1.TryStateMessage
import java.time.Instant as JavaInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

object NodeStackProtobufMapper {

    fun toProto(nodeStack: NodeStack): NodeStackMessage =
        NodeStackMessage(
            frames = nodeStack.map { frame -> frame.toProto() }
        )

    fun fromProto(nodeStack: NodeStackMessage): NodeStack =
        NodeStack(
            nodeStack.frames.map { frame -> frame.toDomain() }
        )

    private fun StackFrame.toProto(): StackFrameMessage =
        StackFrameMessage(
            position = position.toString(),
            state = state.toProto(),
            counter = counter
        )

    private fun StackFrameMessage.toDomain(): StackFrame =
        StackFrame(
            position = NodePosition(position),
            state = state?.toDomain() ?: error("StackFrameMessage.state is required"),
            counter = counter
        )

    private fun NodeState.toProto(): NodeStateMessage = when (this) {
        is RootState -> NodeStateMessage(root = toProto())
        is DoState -> NodeStateMessage(do_state = toProto())
        is ForState -> NodeStateMessage(for_state = toProto())
        is ForeachState -> NodeStateMessage(foreach_state = toProto())
        is CallFunctionState -> NodeStateMessage(call_function_state = toProto())
        is TryState -> NodeStateMessage(try_state = toProto())
        is TaskState -> NodeStateMessage(task_state = toProto())
    }

    private fun NodeStateMessage.toDomain(): NodeState {
        root?.let { return it.toDomain() }
        do_state?.let { return it.toDomain() }
        for_state?.let { return it.toDomain() }
        foreach_state?.let { return it.toDomain() }
        task_state?.let { return it.toDomain() }
        call_function_state?.let { return it.toDomain() }
        try_state?.let { return it.toDomain() }
        error("NodeStateMessage has no state set")
    }

    private fun RootState.toProto(): RootStateMessage =
        RootStateMessage(
            started_at = startedAt.toProtoInstant(),
            workflow_id = workflowId.toString(),
            workflow_input_json = workflowInput.toProtoJsonValue(),
            context_json = context.toProtoJsonStruct(),
            has_waiting_parent = hasWaitingParent
        )

    private fun RootStateMessage.toDomain(): RootState =
        RootState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            workflowId = WorkflowId(IDV7.from(workflow_id)),
            workflowInput = workflow_input_json?.toKotlinJsonElement() ?: buildJsonObject { },
            context = context_json?.toKotlinJsonObject() ?: buildJsonObject { },
            hasWaitingParent = has_waiting_parent
        )

    private fun DoState.toProto(): DoStateMessage =
        DoStateMessage(
            started_at = startedAt.toProtoInstant(),
            index = index
        )

    private fun DoStateMessage.toDomain(): DoState =
        DoState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            index = index
        )

    private fun ForState.toProto(): ForStateMessage =
        ForStateMessage(
            started_at = startedAt.toProtoInstant(),
            collection_json = collection.toProtoJsonListValue(),
            index = index,
            for_each = forEach,
            for_at = forAt
        )

    private fun ForStateMessage.toDomain(): ForState =
        ForState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            collection = collection_json?.toKotlinJsonElementList() ?: emptyList(),
            index = index,
            forEach = for_each,
            forAt = for_at
        )

    private fun ForeachState.toProto(): ForeachStateMessage =
        ForeachStateMessage(
            started_at = startedAt.toProtoInstant(),
            item_json = item.toProtoJsonValue(),
            index = index,
            item_var = itemVar,
            index_var = indexVar
        )

    private fun ForeachStateMessage.toDomain(): ForeachState =
        ForeachState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            item = item_json?.toKotlinJsonElement() ?: JsonNull,
            index = index,
            itemVar = item_var,
            indexVar = index_var
        )

    private fun TaskState.toProto(): TaskStateMessage =
        TaskStateMessage(started_at = startedAt.toProtoInstant())

    private fun TaskStateMessage.toDomain(): TaskState =
        TaskState(startedAt = started_at.toKotlinInstantOrEpoch())

    private fun CallFunctionState.toProto(): CallFunctionStateMessage =
        CallFunctionStateMessage(
            started_at = startedAt.toProtoInstant(),
            entered = entered
        )

    private fun CallFunctionStateMessage.toDomain(): CallFunctionState =
        CallFunctionState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            entered = entered
        )

    private fun TryState.toProto(): TryStateMessage =
        TryStateMessage(
            started_at = startedAt.toProtoInstant(),
            transformed_input_json = transformedInput.toProtoJsonValue(),
            attempt_index = attemptIndex,
            running_catch = runningCatch,
            last_error = lastError?.toProto(),
            error_as = errorAs,
            has_started = hasStarted
        )

    private fun TryStateMessage.toDomain(): TryState =
        TryState(
            startedAt = started_at.toKotlinInstantOrEpoch(),
            transformedInput = transformed_input_json?.toKotlinJsonElement() ?: buildJsonObject { },
            attemptIndex = attempt_index,
            runningCatch = running_catch,
            lastError = last_error?.toDomain(),
            errorAs = error_as,
            hasStarted = has_started
        )

    private fun InternalException.Error.toProto(): InternalErrorMessage =
        InternalErrorMessage(
            type = type,
            status = status,
            position = position,
            title = title,
            details = details
        )

    private fun InternalErrorMessage.toDomain(): InternalException.Error =
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
