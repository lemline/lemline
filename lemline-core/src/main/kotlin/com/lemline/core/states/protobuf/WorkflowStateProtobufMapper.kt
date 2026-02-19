// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states.protobuf

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.HttpAuthentication
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.UntilCondition
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.tasks.FlowDirective
import com.lemline.core.tasks.FlowDirectiveEnum
import com.lemline.core.tasks.FlowDirectiveGoto
import com.lemline.messages.internal.v1.BasicAuthMessage
import com.lemline.messages.internal.v1.BearerAuthMessage
import com.lemline.messages.internal.v1.CallHttpConfigMessage
import com.lemline.messages.internal.v1.CallHttpStartedEvent
import com.lemline.messages.internal.v1.CorrelationDefMessage
import com.lemline.messages.internal.v1.CorrelationMapMessage
import com.lemline.messages.internal.v1.EmitConfigMessage
import com.lemline.messages.internal.v1.EmitStartedEvent
import com.lemline.messages.internal.v1.EventFilterMessage
import com.lemline.messages.internal.v1.FlowDirectiveEnumMessage
import com.lemline.messages.internal.v1.FlowDirectiveMessage
import com.lemline.messages.internal.v1.ForEachCompletedEvent
import com.lemline.messages.internal.v1.ForkBranchCompletedEvent
import com.lemline.messages.internal.v1.ForkBranchFailedEvent
import com.lemline.messages.internal.v1.ForkStartedEvent
import com.lemline.messages.internal.v1.HttpAuthenticationMessage
import com.lemline.messages.internal.v1.HttpOutputMessage
import com.lemline.messages.internal.v1.InternalErrorMessage
import com.lemline.messages.internal.v1.ListenConfigMessage
import com.lemline.messages.internal.v1.ListenReadAsMessage
import com.lemline.messages.internal.v1.ListenStartedEvent
import com.lemline.messages.internal.v1.ListenStrategyMessage
import com.lemline.messages.internal.v1.OAuth2AuthMessage
import com.lemline.messages.internal.v1.ProcessReturnTypeMessage
import com.lemline.messages.internal.v1.ResumeFromTaskCommand
import com.lemline.messages.internal.v1.ResumeWithCompletedTaskCommand
import com.lemline.messages.internal.v1.ResumeWithFailedTaskCommand
import com.lemline.messages.internal.v1.RunScriptConfigMessage
import com.lemline.messages.internal.v1.RunScriptStartedEvent
import com.lemline.messages.internal.v1.RunShellConfigMessage
import com.lemline.messages.internal.v1.RunShellStartedEvent
import com.lemline.messages.internal.v1.RunWorkflowConfigMessage
import com.lemline.messages.internal.v1.RunWorkflowStartedEvent
import com.lemline.messages.internal.v1.StringMapMessage
import com.lemline.messages.internal.v1.TaskRetryScheduledEvent
import com.lemline.messages.internal.v1.TaskScheduledEvent
import com.lemline.messages.internal.v1.UntilConditionMessage
import com.lemline.messages.internal.v1.WaitConfigMessage
import com.lemline.messages.internal.v1.WaitStartedEvent
import com.lemline.messages.internal.v1.WorkflowCommandMessage
import com.lemline.messages.internal.v1.WorkflowCompletedEvent
import com.lemline.messages.internal.v1.WorkflowEventMessage
import com.lemline.messages.internal.v1.WorkflowFailedEvent
import com.lemline.messages.internal.v1.WorkflowStatePayload
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
import java.time.Instant as JavaInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

object WorkflowStateProtobufMapper {

    fun toProto(state: WorkflowState): WorkflowStatePayload =
        when (state) {
            is WorkflowCommand -> WorkflowStatePayload(command = toCommandProto(state))
            is WorkflowEvent -> WorkflowStatePayload(event = toEventProto(state))
        }

    fun fromProto(state: WorkflowStatePayload): WorkflowState {
        state.command?.let { return fromCommandProto(it) }
        state.event?.let { return fromEventProto(it) }
        error("WorkflowStatePayload has no state set")
    }

    fun toCommandProto(command: WorkflowCommand): WorkflowCommandMessage =
        when (command) {
            is WorkflowCommand.ResumeFromTask -> WorkflowCommandMessage(resume_from_task = command.toProto())
            is WorkflowCommand.ResumeWithCompletedTask -> WorkflowCommandMessage(
                resume_with_completed_task = command.toProto()
            )

            is WorkflowCommand.ResumeWithFailedTask -> WorkflowCommandMessage(
                resume_with_failed_task = command.toProto()
            )
        }

    fun fromCommandProto(command: WorkflowCommandMessage): WorkflowCommand {
        command.resume_from_task?.let { return it.toDomain() }
        command.resume_with_completed_task?.let { return it.toDomain() }
        command.resume_with_failed_task?.let { return it.toDomain() }
        error("WorkflowCommandMessage has no command set")
    }

    fun toEventProto(event: WorkflowEvent): WorkflowEventMessage = when (event) {
        is WorkflowEvent.WorkflowCompleted -> WorkflowEventMessage(workflow_completed = event.toProto())
        is WorkflowEvent.WorkflowFailed -> WorkflowEventMessage(workflow_failed = event.toProto())
        is WorkflowEvent.ForkBranchCompleted -> WorkflowEventMessage(fork_branch_completed = event.toProto())
        is WorkflowEvent.ForkBranchFailed -> WorkflowEventMessage(fork_branch_failed = event.toProto())
        is WorkflowEvent.ForEachCompleted -> WorkflowEventMessage(for_each_completed = event.toProto())
        is WorkflowEvent.TaskScheduled -> WorkflowEventMessage(task_scheduled = event.toProto())
        is WorkflowEvent.WaitStarted -> WorkflowEventMessage(wait_started = event.toProto())
        is WorkflowEvent.TaskRetryScheduled -> WorkflowEventMessage(task_retry_scheduled = event.toProto())
        is WorkflowEvent.RunWorkflowStarted -> WorkflowEventMessage(run_workflow_started = event.toProto())
        is WorkflowEvent.ForkStarted -> WorkflowEventMessage(fork_started = event.toProto())
        is WorkflowEvent.ListenStarted -> WorkflowEventMessage(listen_started = event.toProto())
        is WorkflowEvent.EmitStarted -> WorkflowEventMessage(emit_started = event.toProto())
        is WorkflowEvent.CallHttpStarted -> WorkflowEventMessage(call_http_started = event.toProto())
        is WorkflowEvent.RunScriptStarted -> WorkflowEventMessage(run_script_started = event.toProto())
        is WorkflowEvent.RunShellStarted -> WorkflowEventMessage(run_shell_started = event.toProto())
    }

    fun fromEventProto(event: WorkflowEventMessage): WorkflowEvent {
        event.workflow_completed?.let { return it.toDomain() }
        event.workflow_failed?.let { return it.toDomain() }
        event.fork_branch_completed?.let { return it.toDomain() }
        event.fork_branch_failed?.let { return it.toDomain() }
        event.for_each_completed?.let { return it.toDomain() }
        event.task_scheduled?.let { return it.toDomain() }
        event.wait_started?.let { return it.toDomain() }
        event.task_retry_scheduled?.let { return it.toDomain() }
        event.run_workflow_started?.let { return it.toDomain() }
        event.fork_started?.let { return it.toDomain() }
        event.listen_started?.let { return it.toDomain() }
        event.emit_started?.let { return it.toDomain() }
        event.call_http_started?.let { return it.toDomain() }
        event.run_script_started?.let { return it.toDomain() }
        event.run_shell_started?.let { return it.toDomain() }
        error("WorkflowEventMessage has no event set")
    }

    private fun WorkflowCommand.ResumeFromTask.toProto(): ResumeFromTaskCommand =
        ResumeFromTaskCommand(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            node_position = nodePosition.toString(),
            raw_input_json = rawInput.toProtoJsonValue(),
            flow_directive = flowDirective?.toProto()
        )

    private fun ResumeFromTaskCommand.toDomain(): WorkflowCommand.ResumeFromTask =
        WorkflowCommand.ResumeFromTask(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ResumeFromTaskCommand.node_stack is required"),
            nodePosition = NodePosition(node_position),
            rawInput = raw_input_json.toKotlinJsonElement(),
            flowDirective = flow_directive?.toDomain()
        )

    private fun WorkflowCommand.ResumeWithCompletedTask.toProto(): ResumeWithCompletedTaskCommand =
        ResumeWithCompletedTaskCommand(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            raw_output_json = rawOutput.toProtoJsonValue()
        )

    private fun ResumeWithCompletedTaskCommand.toDomain(): WorkflowCommand.ResumeWithCompletedTask =
        WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ResumeWithCompletedTaskCommand.node_stack is required"),
            rawOutput = raw_output_json.toKotlinJsonElement()
        )

    private fun WorkflowCommand.ResumeWithFailedTask.toProto(): ResumeWithFailedTaskCommand =
        ResumeWithFailedTaskCommand(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            error = error.toProto()
        )

    private fun ResumeWithFailedTaskCommand.toDomain(): WorkflowCommand.ResumeWithFailedTask =
        WorkflowCommand.ResumeWithFailedTask(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ResumeWithFailedTaskCommand.node_stack is required"),
            error = error?.toDomain() ?: error("ResumeWithFailedTaskCommand.error is required")
        )

    private fun WorkflowEvent.WorkflowCompleted.toProto(): WorkflowCompletedEvent =
        WorkflowCompletedEvent(
            output_json = output.toProtoJsonValue(),
            completed_at = completedAt.toProtoInstant(),
            node_stack = NodeStackProtobufMapper.toProto(nodeStack)
        )

    private fun WorkflowCompletedEvent.toDomain(): WorkflowEvent.WorkflowCompleted =
        WorkflowEvent.WorkflowCompleted(
            output = output_json.toKotlinJsonElement(),
            completedAt = completed_at.toKotlinInstantOrEpoch(),
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("WorkflowCompletedEvent.node_stack is required"),
        )

    private fun WorkflowEvent.WorkflowFailed.toProto(): WorkflowFailedEvent =
        WorkflowFailedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            raw_input_json = rawInput?.toProtoJsonValue(),
            raw_output_json = rawOutput?.toProtoJsonValue(),
            flow_directive = flowDirective?.toProto(),
            error = error.toProto(),
            failed_at = failedAt.toProtoInstant()
        )

    private fun WorkflowFailedEvent.toDomain(): WorkflowEvent.WorkflowFailed =
        WorkflowEvent.WorkflowFailed(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("WorkflowFailedEvent.node_stack is required"),
            rawInput = raw_input_json?.toKotlinJsonElement(),
            rawOutput = raw_output_json?.toKotlinJsonElement(),
            flowDirective = flow_directive?.toDomain(),
            error = error?.toDomain() ?: error("WorkflowFailedEvent.error is required"),
            failedAt = failed_at.toKotlinInstantOrEpoch(),
        )

    private fun WorkflowEvent.ForkBranchCompleted.toProto(): ForkBranchCompletedEvent =
        ForkBranchCompletedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            branch_position = branchPosition.toString(),
            branch_output_json = branchOutput.toProtoJsonValue(),
            completed_at = completedAt.toProtoInstant()
        )

    private fun ForkBranchCompletedEvent.toDomain(): WorkflowEvent.ForkBranchCompleted =
        WorkflowEvent.ForkBranchCompleted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ForkBranchCompletedEvent.node_stack is required"),
            branchPosition = NodePosition(branch_position),
            branchOutput = branch_output_json.toKotlinJsonElement(),
            completedAt = completed_at.toKotlinInstantOrEpoch(),
        )

    private fun WorkflowEvent.ForkBranchFailed.toProto(): ForkBranchFailedEvent =
        ForkBranchFailedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            branch_position = branchPosition.toString(),
            error = error.toProto(),
            failed_at = failedAt.toProtoInstant()
        )

    private fun ForkBranchFailedEvent.toDomain(): WorkflowEvent.ForkBranchFailed =
        WorkflowEvent.ForkBranchFailed(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ForkBranchFailedEvent.node_stack is required"),
            branchPosition = NodePosition(branch_position),
            error = error?.toDomain() ?: error("ForkBranchFailedEvent.error is required"),
            failedAt = failed_at.toKotlinInstantOrEpoch(),
        )

    private fun WorkflowEvent.ForEachCompleted.toProto(): ForEachCompletedEvent =
        ForEachCompletedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            output_json = output.toProtoJsonValue()
        )

    private fun ForEachCompletedEvent.toDomain(): WorkflowEvent.ForEachCompleted =
        WorkflowEvent.ForEachCompleted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ForEachCompletedEvent.node_stack is required"),
            output = output_json.toKotlinJsonElement(),
        )

    private fun WorkflowEvent.TaskScheduled.toProto(): TaskScheduledEvent =
        TaskScheduledEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            node_position = nodePosition.toString(),
            raw_input_json = rawInput.toProtoJsonValue(),
            flow_directive = flowDirective?.toProto()
        )

    private fun TaskScheduledEvent.toDomain(): WorkflowEvent.TaskScheduled =
        WorkflowEvent.TaskScheduled(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("TaskScheduledEvent.node_stack is required"),
            nodePosition = NodePosition(node_position),
            rawInput = raw_input_json.toKotlinJsonElement(),
            flowDirective = flow_directive?.toDomain(),
        )

    private fun WorkflowEvent.WaitStarted.toProto(): WaitStartedEvent =
        WaitStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            raw_output_json = rawOutput.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun WaitStartedEvent.toDomain(): WorkflowEvent.WaitStarted =
        WorkflowEvent.WaitStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("WaitStartedEvent.node_stack is required"),
            rawOutput = raw_output_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("WaitStartedEvent.config is required"),
        )

    private fun WorkflowEvent.TaskRetryScheduled.toProto(): TaskRetryScheduledEvent =
        TaskRetryScheduledEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            node_position = nodePosition.toString(),
            raw_input_json = rawInput.toProtoJsonValue(),
            flow_directive = flowDirective?.toProto(),
            retry_at = retryAt.toProtoInstant()
        )

    private fun TaskRetryScheduledEvent.toDomain(): WorkflowEvent.TaskRetryScheduled =
        WorkflowEvent.TaskRetryScheduled(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("TaskRetryScheduledEvent.node_stack is required"),
            nodePosition = NodePosition(node_position),
            rawInput = raw_input_json.toKotlinJsonElement(),
            flowDirective = flow_directive?.toDomain(),
            retryAt = retry_at.toKotlinInstantOrEpoch()
        )

    private fun WorkflowEvent.RunWorkflowStarted.toProto(): RunWorkflowStartedEvent =
        RunWorkflowStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            raw_input_json = rawInput.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun RunWorkflowStartedEvent.toDomain(): WorkflowEvent.RunWorkflowStarted =
        WorkflowEvent.RunWorkflowStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("RunWorkflowStartedEvent.node_stack is required"),
            rawInput = raw_input_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("RunWorkflowStartedEvent.config is required")
        )

    private fun WorkflowEvent.ForkStarted.toProto(): ForkStartedEvent =
        ForkStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            raw_input_json = rawInput.toProtoJsonValue()
        )

    private fun ForkStartedEvent.toDomain(): WorkflowEvent.ForkStarted =
        WorkflowEvent.ForkStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ForkStartedEvent.node_stack is required"),
            rawInput = raw_input_json.toKotlinJsonElement(),
        )

    private fun WorkflowEvent.ListenStarted.toProto(): ListenStartedEvent =
        ListenStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            raw_output_json = rawOutput.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun ListenStartedEvent.toDomain(): WorkflowEvent.ListenStarted =
        WorkflowEvent.ListenStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("ListenStartedEvent.node_stack is required"),
            rawOutput = raw_output_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("ListenStartedEvent.config is required")
        )

    private fun WorkflowEvent.EmitStarted.toProto(): EmitStartedEvent =
        EmitStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            input_json = input.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun EmitStartedEvent.toDomain(): WorkflowEvent.EmitStarted =
        WorkflowEvent.EmitStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("EmitStartedEvent.node_stack is required"),
            input = input_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("EmitStartedEvent.config is required")
        )

    private fun WorkflowEvent.CallHttpStarted.toProto(): CallHttpStartedEvent =
        CallHttpStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            input_json = input.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun CallHttpStartedEvent.toDomain(): WorkflowEvent.CallHttpStarted =
        WorkflowEvent.CallHttpStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("CallHttpStartedEvent.node_stack is required"),
            input = input_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("CallHttpStartedEvent.config is required")
        )

    private fun WorkflowEvent.RunScriptStarted.toProto(): RunScriptStartedEvent =
        RunScriptStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            input_json = input.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun RunScriptStartedEvent.toDomain(): WorkflowEvent.RunScriptStarted =
        WorkflowEvent.RunScriptStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("RunScriptStartedEvent.node_stack is required"),
            input = input_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("RunScriptStartedEvent.config is required")
        )

    private fun WorkflowEvent.RunShellStarted.toProto(): RunShellStartedEvent =
        RunShellStartedEvent(
            node_stack = NodeStackProtobufMapper.toProto(nodeStack),
            input_json = input.toProtoJsonValue(),
            config = config.toProto()
        )

    private fun RunShellStartedEvent.toDomain(): WorkflowEvent.RunShellStarted =
        WorkflowEvent.RunShellStarted(
            nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
                ?: error("RunShellStartedEvent.node_stack is required"),
            input = input_json.toKotlinJsonElement(),
            config = config?.toDomain() ?: error("RunShellStartedEvent.config is required")
        )

    private fun WaitConfig.toProto(): WaitConfigMessage =
        WaitConfigMessage(wait_until = waitUntil.toProtoInstant())

    private fun WaitConfigMessage.toDomain(): WaitConfig {
        val waitUntil = wait_until ?: error("WaitConfigMessage.wait_until must be set")
        return WaitConfig(waitUntil = waitUntil.toKotlinInstant())
    }

    private fun RunWorkflowConfig.toProto(): RunWorkflowConfigMessage =
        RunWorkflowConfigMessage(
            namespace = namespace.toString(),
            name = name.toString(),
            version = version.toString(),
            input_json = input.toProtoJsonValue(),
            sync = sync
        )

    private fun RunWorkflowConfigMessage.toDomain(): RunWorkflowConfig =
        RunWorkflowConfig(
            namespace = WorkflowNamespace(namespace),
            name = WorkflowName(name),
            version = WorkflowVersion(version),
            input = input_json.toKotlinJsonElement(),
            sync = sync,
        )

    private fun ListenConfig.toProto(): ListenConfigMessage =
        ListenConfigMessage(
            strategy = strategy.toProto(),
            filters = filters.map { it.toProto() },
            until = until?.toProto(),
            read_as = readAs.toProto(),
            timeout_at = timeoutAt?.toProtoInstant(),
            correlation_context_json = correlationContext?.toProtoJsonValue()
        )

    private fun ListenConfigMessage.toDomain(): ListenConfig =
        ListenConfig(
            strategy = strategy.toDomain(),
            filters = filters.map { it.toDomain() },
            until = until?.toDomain(),
            readAs = read_as.toDomain(),
            timeoutAt = timeout_at?.toKotlinInstant(),
            correlationContext = correlation_context_json?.toKotlinJsonElement(),
        )

    private fun EmitConfig.toProto(): EmitConfigMessage =
        EmitConfigMessage(
            id = id,
            source = source,
            type = type,
            time = time,
            subject = subject,
            dataschema = dataschema,
            datacontenttype = datacontenttype,
            data_json = data?.toProtoJsonValue(),
            extensions = extensions?.toProtoStringMap()
        )

    private fun EmitConfigMessage.toDomain(): EmitConfig =
        EmitConfig(
            id = id,
            source = source,
            type = type,
            time = time,
            subject = subject,
            dataschema = dataschema,
            datacontenttype = datacontenttype,
            data = data_json?.toKotlinJsonElement(),
            extensions = extensions?.toDomainStringMap(),
        )

    private fun CallHttpConfig.toProto(): CallHttpConfigMessage =
        CallHttpConfigMessage(
            method = method,
            url = url,
            headers = headers,
            query = query,
            body_json = body?.toProtoJsonValue(),
            output = output.toProto(),
            redirect = redirect,
            authentication = authentication?.toProto()
        )

    private fun CallHttpConfigMessage.toDomain(): CallHttpConfig =
        CallHttpConfig(
            method = method,
            url = url,
            headers = headers.toMap(),
            query = query.toMap(),
            body = body_json?.toKotlinJsonElement(),
            output = output.toDomain(),
            redirect = redirect,
            authentication = authentication?.toDomain(),
        )

    private fun RunScriptConfig.toProto(): RunScriptConfigMessage =
        RunScriptConfigMessage(
            language = language,
            code = code,
            arguments = arguments?.toProtoStringMap(),
            environment = environment?.toProtoStringMap(),
            await = await,
            return_type = returnType.toProto()
        )

    private fun RunScriptConfigMessage.toDomain(): RunScriptConfig =
        RunScriptConfig(
            language = language,
            code = code,
            arguments = arguments?.toDomainStringMap(),
            environment = environment?.toDomainStringMap(),
            await = await,
            returnType = return_type.toDomain(),
        )

    private fun RunShellConfig.toProto(): RunShellConfigMessage =
        RunShellConfigMessage(
            command = command,
            arguments = arguments?.toProtoStringMap(),
            environment = environment?.toProtoStringMap(),
            await = await,
            return_type = returnType.toProto()
        )

    private fun RunShellConfigMessage.toDomain(): RunShellConfig =
        RunShellConfig(
            command = command,
            arguments = arguments?.toDomainStringMap(),
            environment = environment?.toDomainStringMap(),
            await = await,
            returnType = return_type.toDomain(),
        )

    private fun ListenStrategy.toProto(): ListenStrategyMessage = when (this) {
        ListenStrategy.ONE -> ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_ONE
        ListenStrategy.ANY -> ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_ANY
        ListenStrategy.ALL -> ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_ALL
    }

    private fun ListenStrategyMessage.toDomain(): ListenStrategy = when (this) {
        ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_ONE -> ListenStrategy.ONE
        ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_ANY -> ListenStrategy.ANY
        ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_ALL -> ListenStrategy.ALL
        ListenStrategyMessage.LISTEN_STRATEGY_MESSAGE_UNSPECIFIED -> error("Listen strategy unspecified")
        is ListenStrategyMessage.Unrecognized -> error("Listen strategy unrecognized")
    }

    private fun ListenAndReadAs.toProto(): ListenReadAsMessage = when (this) {
        ListenAndReadAs.DATA -> ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_DATA
        ListenAndReadAs.ENVELOPE -> ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_ENVELOPE
        ListenAndReadAs.RAW -> ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_RAW
    }

    private fun ListenReadAsMessage.toDomain(): ListenAndReadAs = when (this) {
        ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_DATA -> ListenAndReadAs.DATA
        ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_ENVELOPE -> ListenAndReadAs.ENVELOPE
        ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_RAW -> ListenAndReadAs.RAW
        ListenReadAsMessage.LISTEN_READ_AS_MESSAGE_UNSPECIFIED -> error("Listen readAs unspecified")
        is ListenReadAsMessage.Unrecognized -> error("Listen readAs unrecognized")
    }

    private fun EventFilter.toProto(): EventFilterMessage =
        EventFilterMessage(
            type = type,
            source = source,
            subject = subject,
            id = id,
            datacontenttype = datacontenttype,
            dataschema = dataschema,
            time = time,
            data_filter = dataFilter,
            correlations = correlations?.toProto(),
        )

    private fun EventFilterMessage.toDomain(): EventFilter =
        EventFilter(
            type = type,
            source = source,
            subject = subject,
            id = id,
            datacontenttype = datacontenttype,
            dataschema = dataschema,
            time = time,
            dataFilter = data_filter,
            correlations = correlations?.toDomain(),
        )

    private fun Map<String, CorrelationDef>.toProto(): CorrelationMapMessage =
        CorrelationMapMessage(
            values = mapValues { (_, value) -> value.toProto() }
        )

    private fun CorrelationMapMessage.toDomain(): Map<String, CorrelationDef> =
        values.mapValues { (_, value) -> value.toDomain() }

    private fun CorrelationDef.toProto(): CorrelationDefMessage =
        CorrelationDefMessage(
            from = from,
            expect_ = expect
        )

    private fun CorrelationDefMessage.toDomain(): CorrelationDef =
        CorrelationDef(
            from = from,
            expect = expect_,
        )

    private fun UntilCondition.toProto(): UntilConditionMessage =
        when (this) {
            is UntilCondition.Expression -> UntilConditionMessage(expression = expression)
            is UntilCondition.Event -> UntilConditionMessage(event_filter = filter.toProto())
        }

    private fun UntilConditionMessage.toDomain(): UntilCondition {
        expression?.let { return UntilCondition.Expression(it) }
        event_filter?.let { return UntilCondition.Event(it.toDomain()) }
        error("Until condition not set")
    }

    private fun HttpAuthentication.toProto(): HttpAuthenticationMessage =
        when (this) {
            is HttpAuthentication.Basic -> HttpAuthenticationMessage(
                basic = BasicAuthMessage(username = username, password = password)
            )

            is HttpAuthentication.Bearer -> HttpAuthenticationMessage(
                bearer = BearerAuthMessage(token = token)
            )

            is HttpAuthentication.OAuth2 -> HttpAuthenticationMessage(
                oauth2 = OAuth2AuthMessage(token = token, token_type = tokenType)
            )
        }

    private fun HttpAuthenticationMessage.toDomain(): HttpAuthentication {
        basic?.let {
            return HttpAuthentication.Basic(
                username = it.username,
                password = it.password,
            )
        }
        bearer?.let {
            return HttpAuthentication.Bearer(
                token = it.token,
            )
        }
        oauth2?.let {
            return HttpAuthentication.OAuth2(
                token = it.token,
                tokenType = it.token_type,
            )
        }
        error("HTTP authentication not set")
    }

    private fun HTTPOutput.toProto(): HttpOutputMessage = when (this) {
        HTTPOutput.RAW -> HttpOutputMessage.HTTP_OUTPUT_MESSAGE_RAW
        HTTPOutput.CONTENT -> HttpOutputMessage.HTTP_OUTPUT_MESSAGE_CONTENT
        HTTPOutput.RESPONSE -> HttpOutputMessage.HTTP_OUTPUT_MESSAGE_RESPONSE
    }

    private fun HttpOutputMessage.toDomain(): HTTPOutput = when (this) {
        HttpOutputMessage.HTTP_OUTPUT_MESSAGE_RAW -> HTTPOutput.RAW
        HttpOutputMessage.HTTP_OUTPUT_MESSAGE_CONTENT -> HTTPOutput.CONTENT
        HttpOutputMessage.HTTP_OUTPUT_MESSAGE_RESPONSE -> HTTPOutput.RESPONSE
        HttpOutputMessage.HTTP_OUTPUT_MESSAGE_UNSPECIFIED -> error("HTTP output unspecified")
        is HttpOutputMessage.Unrecognized -> error("HTTP output unrecognized")
    }

    private fun ProcessReturnType.toProto(): ProcessReturnTypeMessage = when (this) {
        ProcessReturnType.NONE -> ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_NONE
        ProcessReturnType.STDOUT -> ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_STDOUT
        ProcessReturnType.STDERR -> ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_STDERR
        ProcessReturnType.CODE -> ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_CODE
        ProcessReturnType.ALL -> ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_ALL
    }

    private fun ProcessReturnTypeMessage.toDomain(): ProcessReturnType = when (this) {
        ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_NONE -> ProcessReturnType.NONE
        ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_STDOUT -> ProcessReturnType.STDOUT
        ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_STDERR -> ProcessReturnType.STDERR
        ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_CODE -> ProcessReturnType.CODE
        ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_ALL -> ProcessReturnType.ALL
        ProcessReturnTypeMessage.PROCESS_RETURN_TYPE_MESSAGE_UNSPECIFIED -> error("Process return type unspecified")
        is ProcessReturnTypeMessage.Unrecognized -> error("Process return type unrecognized")
    }

    private fun Map<String, String>.toProtoStringMap(): StringMapMessage =
        StringMapMessage(values = this)

    private fun StringMapMessage.toDomainStringMap(): Map<String, String> = values.toMap()

    private fun FlowDirective.toProto(): FlowDirectiveMessage =
        when (this) {
            is FlowDirectiveEnum.Continue -> FlowDirectiveMessage(
                directive = FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_CONTINUE
            )

            is FlowDirectiveEnum.Exit -> FlowDirectiveMessage(
                directive = FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_EXIT
            )

            is FlowDirectiveEnum.End -> FlowDirectiveMessage(
                directive = FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_END
            )

            is FlowDirectiveGoto -> FlowDirectiveMessage(goto_target = target)
        }

    private fun FlowDirectiveMessage.toDomain(): FlowDirective {
        directive?.let {
            return when (it) {
                FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_CONTINUE -> FlowDirectiveEnum.Continue
                FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_EXIT -> FlowDirectiveEnum.Exit
                FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_END -> FlowDirectiveEnum.End
                FlowDirectiveEnumMessage.FLOW_DIRECTIVE_ENUM_MESSAGE_UNSPECIFIED -> error(
                    "Flow directive enum unspecified"
                )

                is FlowDirectiveEnumMessage.Unrecognized -> error("Unknown flow directive enum")
            }
        }

        goto_target?.let { return FlowDirectiveGoto(it) }
        error("Flow directive not set")
    }

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

    private fun JavaInstant.toKotlinInstant(): Instant =
        Instant.fromEpochSeconds(epochSecond, nano.toLong())

    private fun JavaInstant?.toKotlinInstantOrEpoch(): Instant =
        when (this) {
            null -> Instant.fromEpochSeconds(0)
            else -> toKotlinInstant()
        }
}
