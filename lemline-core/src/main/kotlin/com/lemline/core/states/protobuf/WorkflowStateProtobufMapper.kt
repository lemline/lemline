// SPDX-License-Identifier: BUSL-1.1
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
import com.lemline.messages.internal.v1.BasicAuthProto
import com.lemline.messages.internal.v1.BearerAuthProto
import com.lemline.messages.internal.v1.CallHttpConfigProto
import com.lemline.messages.internal.v1.CallHttpStartedProto
import com.lemline.messages.internal.v1.CorrelationDefProto
import com.lemline.messages.internal.v1.CorrelationMapProto
import com.lemline.messages.internal.v1.EmitConfigProto
import com.lemline.messages.internal.v1.EmitStartedProto
import com.lemline.messages.internal.v1.EventFilterProto
import com.lemline.messages.internal.v1.FlowDirectiveEnumProto
import com.lemline.messages.internal.v1.FlowDirectiveProto
import com.lemline.messages.internal.v1.ForEachCompletedProto
import com.lemline.messages.internal.v1.ForkBranchCompletedProto
import com.lemline.messages.internal.v1.ForkBranchFailedProto
import com.lemline.messages.internal.v1.ForkStartedProto
import com.lemline.messages.internal.v1.HttpAuthenticationProto
import com.lemline.messages.internal.v1.HttpOutputProto
import com.lemline.messages.internal.v1.InternalErrorProto
import com.lemline.messages.internal.v1.ListenConfigProto
import com.lemline.messages.internal.v1.ListenReadAsProto
import com.lemline.messages.internal.v1.ListenStartedProto
import com.lemline.messages.internal.v1.ListenStrategyProto
import com.lemline.messages.internal.v1.OAuth2AuthProto
import com.lemline.messages.internal.v1.ProcessReturnTypeProto
import com.lemline.messages.internal.v1.ResumeFromTaskProto
import com.lemline.messages.internal.v1.ResumeWithCompletedTaskProto
import com.lemline.messages.internal.v1.ResumeWithFailedTaskProto
import com.lemline.messages.internal.v1.RunScriptConfigProto
import com.lemline.messages.internal.v1.RunScriptStartedProto
import com.lemline.messages.internal.v1.RunShellConfigProto
import com.lemline.messages.internal.v1.RunShellStartedProto
import com.lemline.messages.internal.v1.RunWorkflowConfigProto
import com.lemline.messages.internal.v1.RunWorkflowStartedProto
import com.lemline.messages.internal.v1.StringMapProto
import com.lemline.messages.internal.v1.TaskRetryScheduledProto
import com.lemline.messages.internal.v1.TaskScheduledProto
import com.lemline.messages.internal.v1.UntilConditionProto
import com.lemline.messages.internal.v1.WaitConfigProto
import com.lemline.messages.internal.v1.WaitStartedProto
import com.lemline.messages.internal.v1.WorkflowCommandProto
import com.lemline.messages.internal.v1.WorkflowCompletedProto
import com.lemline.messages.internal.v1.WorkflowEventProto
import com.lemline.messages.internal.v1.WorkflowFailedProto
import com.lemline.messages.internal.v1.WorkflowStateProto
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
import java.time.Instant as JavaInstant
import kotlin.time.Instant

object WorkflowStateProtobufMapper {

    fun toProto(state: WorkflowState): WorkflowStateProto =
        when (state) {
            is WorkflowCommand -> WorkflowStateProto(command = toCommandProto(state))
            is WorkflowEvent -> WorkflowStateProto(event = toEventProto(state))
        }

    fun fromProto(state: WorkflowStateProto): WorkflowState {
        state.command?.let { return fromCommandProto(it) }
        state.event?.let { return fromEventProto(it) }
        error("WorkflowStatePayload has no state set")
    }

    fun toCommandProto(command: WorkflowCommand): WorkflowCommandProto =
        when (command) {
            is WorkflowCommand.ResumeFromTask -> WorkflowCommandProto(resume_from_task = command.toProto())
            is WorkflowCommand.ResumeWithCompletedTask -> WorkflowCommandProto(resume_with_completed_task = command.toProto())
            is WorkflowCommand.ResumeWithFailedTask -> WorkflowCommandProto(resume_with_failed_task = command.toProto())
        }

    fun fromCommandProto(command: WorkflowCommandProto): WorkflowCommand {
        command.resume_from_task?.let { return it.toDomain() }
        command.resume_with_completed_task?.let { return it.toDomain() }
        command.resume_with_failed_task?.let { return it.toDomain() }
        error("WorkflowCommandMessage has no command set")
    }

    fun toEventProto(event: WorkflowEvent): WorkflowEventProto = when (event) {
        is WorkflowEvent.WorkflowCompleted -> WorkflowEventProto(workflow_completed = event.toProto())
        is WorkflowEvent.WorkflowFailed -> WorkflowEventProto(workflow_failed = event.toProto())
        is WorkflowEvent.ForkBranchCompleted -> WorkflowEventProto(fork_branch_completed = event.toProto())
        is WorkflowEvent.ForkBranchFailed -> WorkflowEventProto(fork_branch_failed = event.toProto())
        is WorkflowEvent.ForEachCompleted -> WorkflowEventProto(for_each_completed = event.toProto())
        is WorkflowEvent.TaskScheduled -> WorkflowEventProto(task_scheduled = event.toProto())
        is WorkflowEvent.WaitStarted -> WorkflowEventProto(wait_started = event.toProto())
        is WorkflowEvent.TaskRetryScheduled -> WorkflowEventProto(task_retry_scheduled = event.toProto())
        is WorkflowEvent.RunWorkflowStarted -> WorkflowEventProto(run_workflow_started = event.toProto())
        is WorkflowEvent.ForkStarted -> WorkflowEventProto(fork_started = event.toProto())
        is WorkflowEvent.ListenStarted -> WorkflowEventProto(listen_started = event.toProto())
        is WorkflowEvent.EmitStarted -> WorkflowEventProto(emit_started = event.toProto())
        is WorkflowEvent.CallHttpStarted -> WorkflowEventProto(call_http_started = event.toProto())
        is WorkflowEvent.RunScriptStarted -> WorkflowEventProto(run_script_started = event.toProto())
        is WorkflowEvent.RunShellStarted -> WorkflowEventProto(run_shell_started = event.toProto())
    }

    fun fromEventProto(event: WorkflowEventProto): WorkflowEvent {
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

    private fun WorkflowCommand.ResumeFromTask.toProto() = ResumeFromTaskProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        node_position = nodePosition.toString(),
        input_json = rawInput.toProtoJsonValue(),
        flow_directive = flowDirective?.toProto()
    )

    private fun ResumeFromTaskProto.toDomain() = WorkflowCommand.ResumeFromTask(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ResumeFromTaskCommand.node_stack is required"),
        nodePosition = NodePosition(node_position),
        rawInput = input_json.toKotlinJsonElement(),
        flowDirective = flow_directive?.toDomain()
    )

    private fun WorkflowCommand.ResumeWithCompletedTask.toProto() = ResumeWithCompletedTaskProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        output_json = rawOutput.toProtoJsonValue()
    )

    private fun ResumeWithCompletedTaskProto.toDomain() = WorkflowCommand.ResumeWithCompletedTask(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ResumeWithCompletedTaskCommand.node_stack is required"),
        rawOutput = output_json.toKotlinJsonElement()
    )

    private fun WorkflowCommand.ResumeWithFailedTask.toProto() = ResumeWithFailedTaskProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        error = error.toProto()
    )

    private fun ResumeWithFailedTaskProto.toDomain() = WorkflowCommand.ResumeWithFailedTask(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ResumeWithFailedTaskCommand.node_stack is required"),
        error = error?.toDomain() ?: error("ResumeWithFailedTaskCommand.error is required")
    )

    private fun WorkflowEvent.WorkflowCompleted.toProto() = WorkflowCompletedProto(
        output_json = output.toProtoJsonValue(),
        completed_at = completedAt.toProtoInstant(),
        node_stack = NodeStackProtobufMapper.toProto(nodeStack)
    )

    private fun WorkflowCompletedProto.toDomain() = WorkflowEvent.WorkflowCompleted(
        output = output_json.toKotlinJsonElement(),
        completedAt = completed_at.toKotlinInstantOrEpoch(),
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("WorkflowCompletedEvent.node_stack is required"),
    )

    private fun WorkflowEvent.WorkflowFailed.toProto() = WorkflowFailedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = rawInput?.toProtoJsonValue(),
        output_json = rawOutput?.toProtoJsonValue(),
        flow_directive = flowDirective?.toProto(),
        error = error.toProto(),
        failed_at = failedAt.toProtoInstant()
    )

    private fun WorkflowFailedProto.toDomain() = WorkflowEvent.WorkflowFailed(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("WorkflowFailedEvent.node_stack is required"),
        rawInput = input_json?.toKotlinJsonElement(),
        rawOutput = output_json?.toKotlinJsonElement(),
        flowDirective = flow_directive?.toDomain(),
        error = error?.toDomain() ?: error("WorkflowFailedEvent.error is required"),
        failedAt = failed_at.toKotlinInstantOrEpoch(),
    )

    private fun WorkflowEvent.ForkBranchCompleted.toProto() = ForkBranchCompletedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        branch_position = branchPosition.toString(),
        branch_output_json = branchOutput.toProtoJsonValue(),
        completed_at = completedAt.toProtoInstant()
    )

    private fun ForkBranchCompletedProto.toDomain() = WorkflowEvent.ForkBranchCompleted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ForkBranchCompletedEvent.node_stack is required"),
        branchPosition = NodePosition(branch_position),
        branchOutput = branch_output_json.toKotlinJsonElement(),
        completedAt = completed_at.toKotlinInstantOrEpoch(),
    )

    private fun WorkflowEvent.ForkBranchFailed.toProto() = ForkBranchFailedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        branch_position = branchPosition.toString(),
        error = error.toProto(),
        failed_at = failedAt.toProtoInstant()
    )

    private fun ForkBranchFailedProto.toDomain() = WorkflowEvent.ForkBranchFailed(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ForkBranchFailedEvent.node_stack is required"),
        branchPosition = NodePosition(branch_position),
        error = error?.toDomain() ?: error("ForkBranchFailedEvent.error is required"),
        failedAt = failed_at.toKotlinInstantOrEpoch(),
    )

    private fun WorkflowEvent.ForEachCompleted.toProto() = ForEachCompletedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        output_json = output.toProtoJsonValue()
    )

    private fun ForEachCompletedProto.toDomain() = WorkflowEvent.ForEachCompleted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ForEachCompletedEvent.node_stack is required"),
        output = output_json.toKotlinJsonElement(),
    )

    private fun WorkflowEvent.TaskScheduled.toProto() = TaskScheduledProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        node_position = nodePosition.toString(),
        input_json = rawInput.toProtoJsonValue(),
        flow_directive = flowDirective?.toProto()
    )

    private fun TaskScheduledProto.toDomain() = WorkflowEvent.TaskScheduled(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("TaskScheduledEvent.node_stack is required"),
        nodePosition = NodePosition(node_position),
        rawInput = input_json.toKotlinJsonElement(),
        flowDirective = flow_directive?.toDomain(),
    )

    private fun WorkflowEvent.WaitStarted.toProto() = WaitStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        output_json = rawOutput.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun WaitStartedProto.toDomain() = WorkflowEvent.WaitStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("WaitStartedEvent.node_stack is required"),
        rawOutput = output_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("WaitStartedEvent.config is required"),
    )

    private fun WorkflowEvent.TaskRetryScheduled.toProto() = TaskRetryScheduledProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        node_position = nodePosition.toString(),
        input_json = rawInput.toProtoJsonValue(),
        flow_directive = flowDirective?.toProto(),
        retry_at = retryAt.toProtoInstant()
    )

    private fun TaskRetryScheduledProto.toDomain() = WorkflowEvent.TaskRetryScheduled(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("TaskRetryScheduledEvent.node_stack is required"),
        nodePosition = NodePosition(node_position),
        rawInput = input_json.toKotlinJsonElement(),
        flowDirective = flow_directive?.toDomain(),
        retryAt = retry_at.toKotlinInstantOrEpoch()
    )

    private fun WorkflowEvent.RunWorkflowStarted.toProto() = RunWorkflowStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = rawInput.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun RunWorkflowStartedProto.toDomain() = WorkflowEvent.RunWorkflowStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("RunWorkflowStartedEvent.node_stack is required"),
        rawInput = input_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("RunWorkflowStartedEvent.config is required")
    )

    private fun WorkflowEvent.ForkStarted.toProto() = ForkStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = rawInput.toProtoJsonValue()
    )

    private fun ForkStartedProto.toDomain() = WorkflowEvent.ForkStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ForkStartedEvent.node_stack is required"),
        rawInput = input_json.toKotlinJsonElement(),
    )

    private fun WorkflowEvent.ListenStarted.toProto() = ListenStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        output_json = rawOutput.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun ListenStartedProto.toDomain() = WorkflowEvent.ListenStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("ListenStartedEvent.node_stack is required"),
        rawOutput = output_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("ListenStartedEvent.config is required")
    )

    private fun WorkflowEvent.EmitStarted.toProto() = EmitStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = input.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun EmitStartedProto.toDomain() = WorkflowEvent.EmitStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("EmitStartedEvent.node_stack is required"),
        input = input_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("EmitStartedEvent.config is required")
    )

    private fun WorkflowEvent.CallHttpStarted.toProto() = CallHttpStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = input.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun CallHttpStartedProto.toDomain() = WorkflowEvent.CallHttpStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("CallHttpStartedEvent.node_stack is required"),
        input = input_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("CallHttpStartedEvent.config is required")
    )

    private fun WorkflowEvent.RunScriptStarted.toProto() = RunScriptStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = input.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun RunScriptStartedProto.toDomain() = WorkflowEvent.RunScriptStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("RunScriptStartedEvent.node_stack is required"),
        input = input_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("RunScriptStartedEvent.config is required")
    )

    private fun WorkflowEvent.RunShellStarted.toProto() = RunShellStartedProto(
        node_stack = NodeStackProtobufMapper.toProto(nodeStack),
        input_json = input.toProtoJsonValue(),
        config = config.toProto()
    )

    private fun RunShellStartedProto.toDomain() = WorkflowEvent.RunShellStarted(
        nodeStack = node_stack?.let(NodeStackProtobufMapper::fromProto)
            ?: error("RunShellStartedEvent.node_stack is required"),
        input = input_json.toKotlinJsonElement(),
        config = config?.toDomain() ?: error("RunShellStartedEvent.config is required")
    )

    private fun WaitConfig.toProto() = WaitConfigProto(wait_until = waitUntil.toProtoInstant())

    private fun WaitConfigProto.toDomain(): WaitConfig {
        val waitUntil = wait_until ?: error("WaitConfigMessage.wait_until must be set")
        return WaitConfig(waitUntil = waitUntil.toKotlinInstant())
    }

    private fun RunWorkflowConfig.toProto() = RunWorkflowConfigProto(
        namespace = namespace.toString(),
        name = name.toString(),
        version = version.toString(),
        input_json = input.toProtoJsonValue(),
        sync = sync
    )

    private fun RunWorkflowConfigProto.toDomain() = RunWorkflowConfig(
        namespace = WorkflowNamespace(namespace),
        name = WorkflowName(name),
        version = WorkflowVersion(version),
        input = input_json.toKotlinJsonElement(),
        sync = sync,
    )

    private fun ListenConfig.toProto() = ListenConfigProto(
        strategy = strategy.toProto(),
        filters = filters.map { it.toProto() },
        until = until?.toProto(),
        read_as = readAs.toProto(),
        timeout_at = timeoutAt?.toProtoInstant(),
        correlation_context_json = correlationContext?.toProtoJsonValue()
    )

    private fun ListenConfigProto.toDomain() = ListenConfig(
        strategy = strategy.toDomain(),
        filters = filters.map { it.toDomain() },
        until = until?.toDomain(),
        readAs = read_as.toDomain(),
        timeoutAt = timeout_at?.toKotlinInstant(),
        correlationContext = correlation_context_json?.toKotlinJsonElement(),
    )

    private fun EmitConfig.toProto() = EmitConfigProto(
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

    private fun EmitConfigProto.toDomain() = EmitConfig(
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

    private fun CallHttpConfig.toProto() = CallHttpConfigProto(
        method = method,
        url = url,
        headers = headers,
        query = query,
        body_json = body?.toProtoJsonValue(),
        output = output.toProto(),
        redirect = redirect,
        authentication = authentication?.toProto()
    )

    private fun CallHttpConfigProto.toDomain() = CallHttpConfig(
        method = method,
        url = url,
        headers = headers.toMap(),
        query = query.toMap(),
        body = body_json?.toKotlinJsonElement(),
        output = output.toDomain(),
        redirect = redirect,
        authentication = authentication?.toDomain(),
    )

    private fun RunScriptConfig.toProto() = RunScriptConfigProto(
        language = language,
        code = code,
        arguments = arguments?.toProtoStringMap(),
        environment = environment?.toProtoStringMap(),
        await = await,
        return_type = returnType.toProto()
    )

    private fun RunScriptConfigProto.toDomain() = RunScriptConfig(
        language = language,
        code = code,
        arguments = arguments?.toDomainStringMap(),
        environment = environment?.toDomainStringMap(),
        await = await,
        returnType = return_type.toDomain(),
    )

    private fun RunShellConfig.toProto() = RunShellConfigProto(
        command = command,
        arguments = arguments?.toProtoStringMap(),
        environment = environment?.toProtoStringMap(),
        await = await,
        return_type = returnType.toProto()
    )

    private fun RunShellConfigProto.toDomain() = RunShellConfig(
        command = command,
        arguments = arguments?.toDomainStringMap(),
        environment = environment?.toDomainStringMap(),
        await = await,
        returnType = return_type.toDomain(),
    )

    private fun ListenStrategy.toProto(): ListenStrategyProto = when (this) {
        ListenStrategy.ONE -> ListenStrategyProto.LISTEN_STRATEGY_PROTO_ONE
        ListenStrategy.ANY -> ListenStrategyProto.LISTEN_STRATEGY_PROTO_ANY
        ListenStrategy.ALL -> ListenStrategyProto.LISTEN_STRATEGY_PROTO_ALL
    }

    private fun ListenStrategyProto.toDomain(): ListenStrategy = when (this) {
        ListenStrategyProto.LISTEN_STRATEGY_PROTO_ONE -> ListenStrategy.ONE
        ListenStrategyProto.LISTEN_STRATEGY_PROTO_ANY -> ListenStrategy.ANY
        ListenStrategyProto.LISTEN_STRATEGY_PROTO_ALL -> ListenStrategy.ALL
        ListenStrategyProto.LISTEN_STRATEGY_PROTO_UNSPECIFIED -> error("Listen strategy unspecified")
        is ListenStrategyProto.Unrecognized -> error("Listen strategy unrecognized")
    }

    private fun ListenAndReadAs.toProto(): ListenReadAsProto = when (this) {
        ListenAndReadAs.DATA -> ListenReadAsProto.LISTEN_READ_AS_PROTO_DATA
        ListenAndReadAs.ENVELOPE -> ListenReadAsProto.LISTEN_READ_AS_PROTO_ENVELOPE
        ListenAndReadAs.RAW -> ListenReadAsProto.LISTEN_READ_AS_PROTO_RAW
    }

    private fun ListenReadAsProto.toDomain(): ListenAndReadAs = when (this) {
        ListenReadAsProto.LISTEN_READ_AS_PROTO_DATA -> ListenAndReadAs.DATA
        ListenReadAsProto.LISTEN_READ_AS_PROTO_ENVELOPE -> ListenAndReadAs.ENVELOPE
        ListenReadAsProto.LISTEN_READ_AS_PROTO_RAW -> ListenAndReadAs.RAW
        ListenReadAsProto.LISTEN_READ_AS_PROTO_UNSPECIFIED -> error("Listen readAs unspecified")
        is ListenReadAsProto.Unrecognized -> error("Listen readAs unrecognized")
    }

    private fun EventFilter.toProto() = EventFilterProto(
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

    private fun EventFilterProto.toDomain() = EventFilter(
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

    private fun Map<String, CorrelationDef>.toProto() = CorrelationMapProto(
        values = mapValues { (_, value) -> value.toProto() }
    )

    private fun CorrelationMapProto.toDomain(): Map<String, CorrelationDef> =
        values.mapValues { (_, value) -> value.toDomain() }

    private fun CorrelationDef.toProto() = CorrelationDefProto(
        from = from,
        expect_ = expect
    )

    private fun CorrelationDefProto.toDomain() = CorrelationDef(
        from = from,
        expect = expect_,
    )

    private fun UntilCondition.toProto(): UntilConditionProto =
        when (this) {
            is UntilCondition.Expression -> UntilConditionProto(expression = expression)
            is UntilCondition.Event -> UntilConditionProto(event_filter = filter.toProto())
        }

    private fun UntilConditionProto.toDomain(): UntilCondition {
        expression?.let { return UntilCondition.Expression(it) }
        event_filter?.let { return UntilCondition.Event(it.toDomain()) }
        error("Until condition not set")
    }

    private fun HttpAuthentication.toProto(): HttpAuthenticationProto =
        when (this) {
            is HttpAuthentication.Basic -> HttpAuthenticationProto(
                basic = BasicAuthProto(username = username, password = password)
            )

            is HttpAuthentication.Bearer -> HttpAuthenticationProto(
                bearer = BearerAuthProto(token = token)
            )

            is HttpAuthentication.OAuth2 -> HttpAuthenticationProto(
                oauth2 = OAuth2AuthProto(token = token, token_type = tokenType)
            )
        }

    private fun HttpAuthenticationProto.toDomain(): HttpAuthentication {
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

    private fun HTTPOutput.toProto(): HttpOutputProto = when (this) {
        HTTPOutput.RAW -> HttpOutputProto.HTTP_OUTPUT_PROTO_RAW
        HTTPOutput.CONTENT -> HttpOutputProto.HTTP_OUTPUT_PROTO_CONTENT
        HTTPOutput.RESPONSE -> HttpOutputProto.HTTP_OUTPUT_PROTO_RESPONSE
    }

    private fun HttpOutputProto.toDomain(): HTTPOutput = when (this) {
        HttpOutputProto.HTTP_OUTPUT_PROTO_RAW -> HTTPOutput.RAW
        HttpOutputProto.HTTP_OUTPUT_PROTO_CONTENT -> HTTPOutput.CONTENT
        HttpOutputProto.HTTP_OUTPUT_PROTO_RESPONSE -> HTTPOutput.RESPONSE
        HttpOutputProto.HTTP_OUTPUT_PROTO_UNSPECIFIED -> error("HTTP output unspecified")
        is HttpOutputProto.Unrecognized -> error("HTTP output unrecognized")
    }

    private fun ProcessReturnType.toProto(): ProcessReturnTypeProto = when (this) {
        ProcessReturnType.NONE -> ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_NONE
        ProcessReturnType.STDOUT -> ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_STDOUT
        ProcessReturnType.STDERR -> ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_STDERR
        ProcessReturnType.CODE -> ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_CODE
        ProcessReturnType.ALL -> ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_ALL
    }

    private fun ProcessReturnTypeProto.toDomain(): ProcessReturnType = when (this) {
        ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_NONE -> ProcessReturnType.NONE
        ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_STDOUT -> ProcessReturnType.STDOUT
        ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_STDERR -> ProcessReturnType.STDERR
        ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_CODE -> ProcessReturnType.CODE
        ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_ALL -> ProcessReturnType.ALL
        ProcessReturnTypeProto.PROCESS_RETURN_TYPE_PROTO_UNSPECIFIED -> error("Process return type unspecified")
        is ProcessReturnTypeProto.Unrecognized -> error("Process return type unrecognized")
    }

    private fun Map<String, String>.toProtoStringMap(): StringMapProto =
        StringMapProto(values = this)

    private fun StringMapProto.toDomainStringMap(): Map<String, String> = values.toMap()

    private fun FlowDirective.toProto(): FlowDirectiveProto =
        when (this) {
            is FlowDirectiveEnum.Continue -> FlowDirectiveProto(
                directive = FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_CONTINUE
            )

            is FlowDirectiveEnum.Exit -> FlowDirectiveProto(
                directive = FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_EXIT
            )

            is FlowDirectiveEnum.End -> FlowDirectiveProto(
                directive = FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_END
            )

            is FlowDirectiveGoto -> FlowDirectiveProto(goto_target = target)
        }

    private fun FlowDirectiveProto.toDomain(): FlowDirective {
        directive?.let {
            return when (it) {
                FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_CONTINUE -> FlowDirectiveEnum.Continue
                FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_EXIT -> FlowDirectiveEnum.Exit
                FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_END -> FlowDirectiveEnum.End
                FlowDirectiveEnumProto.FLOW_DIRECTIVE_ENUM_PROTO_UNSPECIFIED -> error(
                    "Flow directive enum unspecified"
                )

                is FlowDirectiveEnumProto.Unrecognized -> error("Unknown flow directive enum")
            }
        }

        goto_target?.let { return FlowDirectiveGoto(it) }
        error("Flow directive not set")
    }

    private fun InternalException.Error.toProto() = InternalErrorProto(
        type = type,
        status = status,
        position = position,
        title = title,
        details = details
    )

    private fun InternalErrorProto.toDomain() = InternalException.Error(
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
