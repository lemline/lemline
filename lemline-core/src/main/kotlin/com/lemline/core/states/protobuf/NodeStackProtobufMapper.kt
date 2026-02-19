// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states.protobuf

import com.google.protobuf.Timestamp
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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

object NodeStackProtobufMapper {

    fun toProto(nodeStack: NodeStack): NodeStackMessage =
        NodeStackMessage.newBuilder()
            .addAllFrames(nodeStack.map { frame -> frame.toProto() })
            .build()

    fun fromProto(nodeStack: NodeStackMessage): NodeStack =
        NodeStack(
            nodeStack.framesList.map { frame -> frame.toDomain() }
        )

    private fun StackFrame.toProto(): StackFrameMessage =
        StackFrameMessage.newBuilder()
            .setPosition(position.toString())
            .setState(state.toProto())
            .setCounter(counter)
            .build()

    private fun StackFrameMessage.toDomain(): StackFrame =
        StackFrame(
            position = NodePosition(position),
            state = state.toDomain(),
            counter = counter
        )

    private fun NodeState.toProto(): NodeStateMessage = when (this) {
        is RootState -> NodeStateMessage.newBuilder().setRoot(toProto()).build()
        is DoState -> NodeStateMessage.newBuilder().setDoState(toProto()).build()
        is ForState -> NodeStateMessage.newBuilder().setForState(toProto()).build()
        is ForeachState -> NodeStateMessage.newBuilder().setForeachState(toProto()).build()
        is CallFunctionState -> NodeStateMessage.newBuilder().setCallFunctionState(toProto()).build()
        is TryState -> NodeStateMessage.newBuilder().setTryState(toProto()).build()
        is TaskState -> NodeStateMessage.newBuilder().setTaskState(toProto()).build()
    }

    private fun NodeStateMessage.toDomain(): NodeState = when (stateCase) {
        NodeStateMessage.StateCase.ROOT -> root.toDomain()
        NodeStateMessage.StateCase.DO_STATE -> doState.toDomain()
        NodeStateMessage.StateCase.FOR_STATE -> forState.toDomain()
        NodeStateMessage.StateCase.FOREACH_STATE -> foreachState.toDomain()
        NodeStateMessage.StateCase.TASK_STATE -> taskState.toDomain()
        NodeStateMessage.StateCase.CALL_FUNCTION_STATE -> callFunctionState.toDomain()
        NodeStateMessage.StateCase.TRY_STATE -> tryState.toDomain()
        NodeStateMessage.StateCase.STATE_NOT_SET -> error("NodeStateMessage has no state set")
        null -> error("NodeStateMessage has unknown state")
    }

    private fun RootState.toProto(): RootStateMessage =
        RootStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .setWorkflowId(workflowId.toString())
            .setWorkflowInputJson(workflowInput.toProtoJsonValue())
            .setContextJson(context.toProtoJsonStruct())
            .setHasWaitingParent(hasWaitingParent)
            .build()

    private fun RootStateMessage.toDomain(): RootState =
        RootState(
            startedAt = startedAt.toInstantOrEpoch(hasStartedAt()),
            workflowId = WorkflowId(IDV7.from(workflowId)),
            workflowInput = when (hasWorkflowInputJson()) {
                true -> workflowInputJson.toKotlinJsonElement()
                false -> buildJsonObject { }
            },
            context = when (hasContextJson()) {
                true -> contextJson.toKotlinJsonObject()
                false -> buildJsonObject { }
            },
            hasWaitingParent = hasWaitingParent
        )

    private fun DoState.toProto(): DoStateMessage =
        DoStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .setIndex(index)
            .build()

    private fun DoStateMessage.toDomain(): DoState =
        DoState(
            startedAt = startedAt.toInstantOrEpoch(hasStartedAt()),
            index = index
        )

    private fun ForState.toProto(): ForStateMessage =
        ForStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .setCollectionJson(collection.toProtoJsonListValue())
            .setIndex(index)
            .setForEach(forEach)
            .setForAt(forAt)
            .build()

    private fun ForStateMessage.toDomain(): ForState =
        ForState(
            startedAt = startedAt.toInstantOrEpoch(hasStartedAt()),
            collection = when (hasCollectionJson()) {
                true -> collectionJson.toKotlinJsonElementList()
                false -> emptyList()
            },
            index = index,
            forEach = forEach,
            forAt = forAt
        )

    private fun ForeachState.toProto(): ForeachStateMessage =
        ForeachStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .setItemJson(item.toProtoJsonValue())
            .setIndex(index)
            .setItemVar(itemVar)
            .setIndexVar(indexVar)
            .build()

    private fun ForeachStateMessage.toDomain(): ForeachState =
        ForeachState(
            startedAt = startedAt.toInstantOrEpoch(hasStartedAt()),
            item = when (hasItemJson()) {
                true -> itemJson.toKotlinJsonElement()
                false -> JsonNull
            },
            index = index,
            itemVar = itemVar,
            indexVar = indexVar
        )

    private fun TaskState.toProto(): TaskStateMessage =
        TaskStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .build()

    private fun TaskStateMessage.toDomain(): TaskState =
        TaskState(startedAt = startedAt.toInstantOrEpoch(hasStartedAt()))

    private fun CallFunctionState.toProto(): CallFunctionStateMessage =
        CallFunctionStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .setEntered(entered)
            .build()

    private fun CallFunctionStateMessage.toDomain(): CallFunctionState =
        CallFunctionState(
            startedAt = startedAt.toInstantOrEpoch(hasStartedAt()),
            entered = entered
        )

    private fun TryState.toProto(): TryStateMessage =
        TryStateMessage.newBuilder()
            .setStartedAt(startedAt.toProto())
            .setTransformedInputJson(transformedInput.toProtoJsonValue())
            .setAttemptIndex(attemptIndex)
            .setRunningCatch(runningCatch)
            .apply { this@toProto.lastError?.let { setLastError(it.toProto()) } }
            .setErrorAs(errorAs)
            .setHasStarted(hasStarted)
            .build()

    private fun TryStateMessage.toDomain(): TryState =
        TryState(
            startedAt = startedAt.toInstantOrEpoch(hasStartedAt()),
            transformedInput = when (hasTransformedInputJson()) {
                true -> transformedInputJson.toKotlinJsonElement()
                false -> buildJsonObject { }
            },
            attemptIndex = attemptIndex,
            runningCatch = runningCatch,
            lastError = when (hasLastError()) {
                true -> lastError.toDomain()
                false -> null
            },
            errorAs = errorAs,
            hasStarted = hasStarted
        )

    private fun InternalException.Error.toProto(): InternalErrorMessage =
        InternalErrorMessage.newBuilder()
            .setType(type)
            .setStatus(status)
            .setPosition(position)
            .apply { this@toProto.title?.let { setTitle(it) } }
            .apply { this@toProto.details?.let { setDetails(it) } }
            .build()

    private fun InternalErrorMessage.toDomain(): InternalException.Error =
        InternalException.Error(
            type = type,
            status = status,
            position = position,
            title = if (hasTitle()) title else null,
            details = if (hasDetails()) details else null
        )

    private fun Instant.toProto(): Timestamp =
        Timestamp.newBuilder()
            .setSeconds(epochSeconds)
            .setNanos(nanosecondsOfSecond)
            .build()

    private fun Timestamp.toInstantOrEpoch(hasValue: Boolean): Instant =
        when (hasValue) {
            true -> Instant.fromEpochSeconds(seconds, nanos.toLong())
            false -> Instant.fromEpochSeconds(0)
        }
}
