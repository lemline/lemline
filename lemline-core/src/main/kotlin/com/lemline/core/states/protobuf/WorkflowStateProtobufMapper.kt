// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states.protobuf

import com.google.protobuf.Timestamp
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.tasks.FlowDirective
import com.lemline.core.tasks.FlowDirectiveEnum
import com.lemline.core.tasks.FlowDirectiveGoto
import com.lemline.messages.internal.v1.CallHttpStartedEvent
import com.lemline.messages.internal.v1.EmitStartedEvent
import com.lemline.messages.internal.v1.FlowDirectiveEnumMessage
import com.lemline.messages.internal.v1.FlowDirectiveMessage
import com.lemline.messages.internal.v1.ForEachCompletedEvent
import com.lemline.messages.internal.v1.ForkBranchCompletedEvent
import com.lemline.messages.internal.v1.ForkBranchFailedEvent
import com.lemline.messages.internal.v1.ForkStartedEvent
import com.lemline.messages.internal.v1.InternalErrorMessage
import com.lemline.messages.internal.v1.ListenStartedEvent
import com.lemline.messages.internal.v1.ResumeFromTaskCommand
import com.lemline.messages.internal.v1.ResumeWithCompletedTaskCommand
import com.lemline.messages.internal.v1.ResumeWithFailedTaskCommand
import com.lemline.messages.internal.v1.RunScriptStartedEvent
import com.lemline.messages.internal.v1.RunShellStartedEvent
import com.lemline.messages.internal.v1.RunWorkflowStartedEvent
import com.lemline.messages.internal.v1.TaskRetryScheduledEvent
import com.lemline.messages.internal.v1.TaskScheduledEvent
import com.lemline.messages.internal.v1.WaitStartedEvent
import com.lemline.messages.internal.v1.WorkflowCommandMessage
import com.lemline.messages.internal.v1.WorkflowCompletedEvent
import com.lemline.messages.internal.v1.WorkflowEventMessage
import com.lemline.messages.internal.v1.WorkflowFailedEvent
import com.lemline.messages.internal.v1.WorkflowStatePayload
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement

object WorkflowStateProtobufMapper {

    fun toProto(state: WorkflowState): WorkflowStatePayload =
        when (state) {
            is WorkflowCommand -> WorkflowStatePayload.newBuilder()
                .setCommand(toCommandProto(state))
                .build()

            is WorkflowEvent -> WorkflowStatePayload.newBuilder()
                .setEvent(toEventProto(state))
                .build()
        }

    fun fromProto(state: WorkflowStatePayload): WorkflowState = when (state.stateCase) {
        WorkflowStatePayload.StateCase.COMMAND -> fromCommandProto(state.command)
        WorkflowStatePayload.StateCase.EVENT -> fromEventProto(state.event)
        WorkflowStatePayload.StateCase.STATE_NOT_SET -> error("WorkflowStatePayload has no state set")
        null -> error("WorkflowStatePayload has unknown state")
    }

    fun toCommandProto(command: WorkflowCommand): WorkflowCommandMessage =
        when (command) {
            is WorkflowCommand.ResumeFromTask -> WorkflowCommandMessage.newBuilder()
                .setResumeFromTask(command.toProto())
                .build()

            is WorkflowCommand.ResumeWithCompletedTask -> WorkflowCommandMessage.newBuilder()
                .setResumeWithCompletedTask(command.toProto())
                .build()

            is WorkflowCommand.ResumeWithFailedTask -> WorkflowCommandMessage.newBuilder()
                .setResumeWithFailedTask(command.toProto())
                .build()
        }

    fun fromCommandProto(command: WorkflowCommandMessage): WorkflowCommand = when (command.commandCase) {
        WorkflowCommandMessage.CommandCase.RESUME_FROM_TASK -> command.resumeFromTask.toDomain()
        WorkflowCommandMessage.CommandCase.RESUME_WITH_COMPLETED_TASK -> command.resumeWithCompletedTask.toDomain()
        WorkflowCommandMessage.CommandCase.RESUME_WITH_FAILED_TASK -> command.resumeWithFailedTask.toDomain()
        WorkflowCommandMessage.CommandCase.COMMAND_NOT_SET -> error("WorkflowCommandMessage has no command set")
        null -> error("WorkflowCommandMessage has unknown command")
    }

    fun toEventProto(event: WorkflowEvent): WorkflowEventMessage = when (event) {
        is WorkflowEvent.WorkflowCompleted -> WorkflowEventMessage.newBuilder()
            .setWorkflowCompleted(event.toProto())
            .build()

        is WorkflowEvent.WorkflowFailed -> WorkflowEventMessage.newBuilder()
            .setWorkflowFailed(event.toProto())
            .build()

        is WorkflowEvent.ForkBranchCompleted -> WorkflowEventMessage.newBuilder()
            .setForkBranchCompleted(event.toProto())
            .build()

        is WorkflowEvent.ForkBranchFailed -> WorkflowEventMessage.newBuilder()
            .setForkBranchFailed(event.toProto())
            .build()

        is WorkflowEvent.ForEachCompleted -> WorkflowEventMessage.newBuilder()
            .setForEachCompleted(event.toProto())
            .build()

        is WorkflowEvent.TaskScheduled -> WorkflowEventMessage.newBuilder()
            .setTaskScheduled(event.toProto())
            .build()

        is WorkflowEvent.WaitStarted -> WorkflowEventMessage.newBuilder()
            .setWaitStarted(event.toProto())
            .build()

        is WorkflowEvent.TaskRetryScheduled -> WorkflowEventMessage.newBuilder()
            .setTaskRetryScheduled(event.toProto())
            .build()

        is WorkflowEvent.RunWorkflowStarted -> WorkflowEventMessage.newBuilder()
            .setRunWorkflowStarted(event.toProto())
            .build()

        is WorkflowEvent.ForkStarted -> WorkflowEventMessage.newBuilder()
            .setForkStarted(event.toProto())
            .build()

        is WorkflowEvent.ListenStarted -> WorkflowEventMessage.newBuilder()
            .setListenStarted(event.toProto())
            .build()

        is WorkflowEvent.EmitStarted -> WorkflowEventMessage.newBuilder()
            .setEmitStarted(event.toProto())
            .build()

        is WorkflowEvent.CallHttpStarted -> WorkflowEventMessage.newBuilder()
            .setCallHttpStarted(event.toProto())
            .build()

        is WorkflowEvent.RunScriptStarted -> WorkflowEventMessage.newBuilder()
            .setRunScriptStarted(event.toProto())
            .build()

        is WorkflowEvent.RunShellStarted -> WorkflowEventMessage.newBuilder()
            .setRunShellStarted(event.toProto())
            .build()
    }

    fun fromEventProto(event: WorkflowEventMessage): WorkflowEvent = when (event.eventCase) {
        WorkflowEventMessage.EventCase.WORKFLOW_COMPLETED -> event.workflowCompleted.toDomain()
        WorkflowEventMessage.EventCase.WORKFLOW_FAILED -> event.workflowFailed.toDomain()
        WorkflowEventMessage.EventCase.FORK_BRANCH_COMPLETED -> event.forkBranchCompleted.toDomain()
        WorkflowEventMessage.EventCase.FORK_BRANCH_FAILED -> event.forkBranchFailed.toDomain()
        WorkflowEventMessage.EventCase.FOR_EACH_COMPLETED -> event.forEachCompleted.toDomain()
        WorkflowEventMessage.EventCase.TASK_SCHEDULED -> event.taskScheduled.toDomain()
        WorkflowEventMessage.EventCase.WAIT_STARTED -> event.waitStarted.toDomain()
        WorkflowEventMessage.EventCase.TASK_RETRY_SCHEDULED -> event.taskRetryScheduled.toDomain()
        WorkflowEventMessage.EventCase.RUN_WORKFLOW_STARTED -> event.runWorkflowStarted.toDomain()
        WorkflowEventMessage.EventCase.FORK_STARTED -> event.forkStarted.toDomain()
        WorkflowEventMessage.EventCase.LISTEN_STARTED -> event.listenStarted.toDomain()
        WorkflowEventMessage.EventCase.EMIT_STARTED -> event.emitStarted.toDomain()
        WorkflowEventMessage.EventCase.CALL_HTTP_STARTED -> event.callHttpStarted.toDomain()
        WorkflowEventMessage.EventCase.RUN_SCRIPT_STARTED -> event.runScriptStarted.toDomain()
        WorkflowEventMessage.EventCase.RUN_SHELL_STARTED -> event.runShellStarted.toDomain()
        WorkflowEventMessage.EventCase.EVENT_NOT_SET -> error("WorkflowEventMessage has no event set")
        null -> error("WorkflowEventMessage has unknown event")
    }

    private fun WorkflowCommand.ResumeFromTask.toProto(): ResumeFromTaskCommand =
        ResumeFromTaskCommand.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setNodePosition(nodePosition.toString())
            .setRawInputJson(rawInput.toJsonString())
            .apply { this@toProto.flowDirective?.let { setFlowDirective(it.toProto()) } }
            .build()

    private fun ResumeFromTaskCommand.toDomain(): WorkflowCommand.ResumeFromTask =
        WorkflowCommand.ResumeFromTask(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            nodePosition = NodePosition(nodePosition),
            rawInput = rawInputJson.decodeJson(),
            flowDirective = when (hasFlowDirective()) {
                true -> flowDirective.toDomain()
                false -> null
            }
        )

    private fun WorkflowCommand.ResumeWithCompletedTask.toProto(): ResumeWithCompletedTaskCommand =
        ResumeWithCompletedTaskCommand.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setRawOutputJson(rawOutput.toJsonString())
            .build()

    private fun ResumeWithCompletedTaskCommand.toDomain(): WorkflowCommand.ResumeWithCompletedTask =
        WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            rawOutput = rawOutputJson.decodeJson()
        )

    private fun WorkflowCommand.ResumeWithFailedTask.toProto(): ResumeWithFailedTaskCommand =
        ResumeWithFailedTaskCommand.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setError(error.toProto())
            .build()

    private fun ResumeWithFailedTaskCommand.toDomain(): WorkflowCommand.ResumeWithFailedTask =
        WorkflowCommand.ResumeWithFailedTask(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            error = error.toDomain()
        )

    private fun WorkflowEvent.WorkflowCompleted.toProto(): WorkflowCompletedEvent =
        WorkflowCompletedEvent.newBuilder()
            .setOutputJson(output.toJsonString())
            .setCompletedAt(completedAt.toProto())
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .build()

    private fun WorkflowCompletedEvent.toDomain(): WorkflowEvent.WorkflowCompleted =
        WorkflowEvent.WorkflowCompleted(
            output = outputJson.decodeJson(),
            completedAt = completedAt.toInstant(),
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
        )

    private fun WorkflowEvent.WorkflowFailed.toProto(): WorkflowFailedEvent =
        WorkflowFailedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .apply { rawInput?.let { setRawInputJson(it.toJsonString()) } }
            .apply { rawOutput?.let { setRawOutputJson(it.toJsonString()) } }
            .apply { this@toProto.flowDirective?.let { setFlowDirective(it.toProto()) } }
            .setError(error.toProto())
            .setFailedAt(failedAt.toProto())
            .build()

    private fun WorkflowFailedEvent.toDomain(): WorkflowEvent.WorkflowFailed =
        WorkflowEvent.WorkflowFailed(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            rawInput = when (hasRawInputJson()) {
                true -> rawInputJson.decodeJson()
                false -> null
            },
            rawOutput = when (hasRawOutputJson()) {
                true -> rawOutputJson.decodeJson()
                false -> null
            },
            flowDirective = when (hasFlowDirective()) {
                true -> flowDirective.toDomain()
                false -> null
            },
            error = error.toDomain(),
            failedAt = failedAt.toInstant(),
        )

    private fun WorkflowEvent.ForkBranchCompleted.toProto(): ForkBranchCompletedEvent =
        ForkBranchCompletedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setBranchPosition(branchPosition.toString())
            .setBranchOutputJson(branchOutput.toJsonString())
            .setCompletedAt(completedAt.toProto())
            .build()

    private fun ForkBranchCompletedEvent.toDomain(): WorkflowEvent.ForkBranchCompleted =
        WorkflowEvent.ForkBranchCompleted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            branchPosition = NodePosition(branchPosition),
            branchOutput = branchOutputJson.decodeJson(),
            completedAt = completedAt.toInstant(),
        )

    private fun WorkflowEvent.ForkBranchFailed.toProto(): ForkBranchFailedEvent =
        ForkBranchFailedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setBranchPosition(branchPosition.toString())
            .setError(error.toProto())
            .setFailedAt(failedAt.toProto())
            .build()

    private fun ForkBranchFailedEvent.toDomain(): WorkflowEvent.ForkBranchFailed =
        WorkflowEvent.ForkBranchFailed(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            branchPosition = NodePosition(branchPosition),
            error = error.toDomain(),
            failedAt = failedAt.toInstant(),
        )

    private fun WorkflowEvent.ForEachCompleted.toProto(): ForEachCompletedEvent =
        ForEachCompletedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setOutputJson(output.toJsonString())
            .build()

    private fun ForEachCompletedEvent.toDomain(): WorkflowEvent.ForEachCompleted =
        WorkflowEvent.ForEachCompleted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            output = outputJson.decodeJson(),
        )

    private fun WorkflowEvent.TaskScheduled.toProto(): TaskScheduledEvent =
        TaskScheduledEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setNodePosition(nodePosition.toString())
            .setRawInputJson(rawInput.toJsonString())
            .apply { this@toProto.flowDirective?.let { setFlowDirective(it.toProto()) } }
            .build()

    private fun TaskScheduledEvent.toDomain(): WorkflowEvent.TaskScheduled =
        WorkflowEvent.TaskScheduled(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            nodePosition = NodePosition(nodePosition),
            rawInput = rawInputJson.decodeJson(),
            flowDirective = when (hasFlowDirective()) {
                true -> flowDirective.toDomain()
                false -> null
            },
        )

    private fun WorkflowEvent.WaitStarted.toProto(): WaitStartedEvent =
        WaitStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setRawOutputJson(rawOutput.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun WaitStartedEvent.toDomain(): WorkflowEvent.WaitStarted =
        WorkflowEvent.WaitStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            rawOutput = rawOutputJson.decodeJson(),
            config = configJson.decodeJson(),
        )

    private fun WorkflowEvent.TaskRetryScheduled.toProto(): TaskRetryScheduledEvent =
        TaskRetryScheduledEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setNodePosition(nodePosition.toString())
            .setRawInputJson(rawInput.toJsonString())
            .apply { this@toProto.flowDirective?.let { setFlowDirective(it.toProto()) } }
            .setRetryAt(retryAt.toProto())
            .build()

    private fun TaskRetryScheduledEvent.toDomain(): WorkflowEvent.TaskRetryScheduled =
        WorkflowEvent.TaskRetryScheduled(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            nodePosition = NodePosition(nodePosition),
            rawInput = rawInputJson.decodeJson(),
            flowDirective = when (hasFlowDirective()) {
                true -> flowDirective.toDomain()
                false -> null
            },
            retryAt = retryAt.toInstant()
        )

    private fun WorkflowEvent.RunWorkflowStarted.toProto(): RunWorkflowStartedEvent =
        RunWorkflowStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setRawInputJson(rawInput.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun RunWorkflowStartedEvent.toDomain(): WorkflowEvent.RunWorkflowStarted =
        WorkflowEvent.RunWorkflowStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            rawInput = rawInputJson.decodeJson(),
            config = configJson.decodeJson()
        )

    private fun WorkflowEvent.ForkStarted.toProto(): ForkStartedEvent =
        ForkStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setRawInputJson(rawInput.toJsonString())
            .build()

    private fun ForkStartedEvent.toDomain(): WorkflowEvent.ForkStarted =
        WorkflowEvent.ForkStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            rawInput = rawInputJson.decodeJson(),
        )

    private fun WorkflowEvent.ListenStarted.toProto(): ListenStartedEvent =
        ListenStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setRawOutputJson(rawOutput.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun ListenStartedEvent.toDomain(): WorkflowEvent.ListenStarted =
        WorkflowEvent.ListenStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            rawOutput = rawOutputJson.decodeJson(),
            config = configJson.decodeJson()
        )

    private fun WorkflowEvent.EmitStarted.toProto(): EmitStartedEvent =
        EmitStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setInputJson(input.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun EmitStartedEvent.toDomain(): WorkflowEvent.EmitStarted =
        WorkflowEvent.EmitStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            input = inputJson.decodeJson(),
            config = configJson.decodeJson()
        )

    private fun WorkflowEvent.CallHttpStarted.toProto(): CallHttpStartedEvent =
        CallHttpStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setInputJson(input.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun CallHttpStartedEvent.toDomain(): WorkflowEvent.CallHttpStarted =
        WorkflowEvent.CallHttpStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            input = inputJson.decodeJson(),
            config = configJson.decodeJson()
        )

    private fun WorkflowEvent.RunScriptStarted.toProto(): RunScriptStartedEvent =
        RunScriptStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setInputJson(input.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun RunScriptStartedEvent.toDomain(): WorkflowEvent.RunScriptStarted =
        WorkflowEvent.RunScriptStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            input = inputJson.decodeJson(),
            config = configJson.decodeJson()
        )

    private fun WorkflowEvent.RunShellStarted.toProto(): RunShellStartedEvent =
        RunShellStartedEvent.newBuilder()
            .setNodeStack(NodeStackProtobufMapper.toProto(nodeStack))
            .setInputJson(input.toJsonString())
            .setConfigJson(config.toJsonString())
            .build()

    private fun RunShellStartedEvent.toDomain(): WorkflowEvent.RunShellStarted =
        WorkflowEvent.RunShellStarted(
            nodeStack = NodeStackProtobufMapper.fromProto(nodeStack),
            input = inputJson.decodeJson(),
            config = configJson.decodeJson()
        )

    private fun FlowDirective.toProto(): FlowDirectiveMessage =
        FlowDirectiveMessage.newBuilder()
            .apply {
                when (this@toProto) {
                    is FlowDirectiveEnum.Continue -> setDirective(FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_CONTINUE)
                    is FlowDirectiveEnum.Exit -> setDirective(FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_EXIT)
                    is FlowDirectiveEnum.End -> setDirective(FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_END)
                    is FlowDirectiveGoto -> setGotoTarget(target)
                }
            }
            .build()

    private fun FlowDirectiveMessage.toDomain(): FlowDirective = when (valueCase) {
        FlowDirectiveMessage.ValueCase.DIRECTIVE -> when (directive) {
            FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_CONTINUE -> FlowDirectiveEnum.Continue
            FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_EXIT -> FlowDirectiveEnum.Exit
            FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_END -> FlowDirectiveEnum.End
            FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_UNSPECIFIED -> error("Flow directive enum unspecified")
            FlowDirectiveEnumMessage.UNRECOGNIZED -> error("Unknown flow directive enum")
            null -> error("Flow directive enum is null")
        }

        FlowDirectiveMessage.ValueCase.GOTO_TARGET -> FlowDirectiveGoto(gotoTarget)
        FlowDirectiveMessage.ValueCase.VALUE_NOT_SET -> error("Flow directive not set")
        null -> error("Flow directive unknown")
    }

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

    private fun Timestamp.toInstant(): Instant = Instant.fromEpochSeconds(seconds, nanos.toLong())

    private fun JsonElement.toJsonString(): String = LemlineJson.encodeToString(this)

    private inline fun <reified T> T.toJsonString(): String = LemlineJson.encodeToString(this)

    private inline fun <reified T> String.decodeJson(): T = LemlineJson.decodeFromString(this)
}
