// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.CallA2AConfig
import com.lemline.core.processors.HttpAuthentication
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.EventFilter
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.UntilCondition
import com.lemline.core.processors.WaitConfig
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TaskState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.tasks.FlowDirectiveEnum
import com.lemline.core.tasks.FlowDirectiveGoto
import com.lemline.core.workflows.CachedEventConsumption
import com.lemline.core.workflows.CachedEventFilter
import com.lemline.messages.internal.v1.StringMapProto
import com.lemline.messages.internal.v1.WorkflowStateProto
import io.serverlessworkflow.api.types.A2AArguments.WithA2AMethod
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.jupiter.api.Test

class WorkflowStateProtobufMapperTest {

    @Test
    fun `shell ordered arguments and stdin survive binary transport`() {
        val original = eventRunShellStarted().let {
            it.copy(config = it.config.copy(arguments = listOf("-v", "", "-v", "two words"), stdin = "input\n"))
        }
        val encoded = WorkflowStateProtobufMapper.toProto(original).encode()
        assertEquals(original, WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(encoded)))
    }

    @Test
    fun `legacy shell argument maps remain readable`() {
        val original = WorkflowStateProtobufMapper.toProto(eventRunShellStarted())
        val event = original.event!!
        val shell = event.run_shell_started!!
        val legacy = original.copy(event = event.copy(run_shell_started = shell.copy(config = shell.config!!.copy(
            arguments = StringMapProto(values = linkedMapOf("-n" to "", "hello" to "world")),
        ))))
        val decoded = WorkflowStateProtobufMapper.fromProto(WorkflowStateProto.ADAPTER.decode(legacy.encode()))
            as WorkflowEvent.RunShellStarted
        assertEquals(listOf("-n", "hello", "world"), decoded.config.arguments)
        assertEquals(null, decoded.config.stdin)
    }

    @Test
    fun `complete until strategy preserves extension values in protobuf`() {
        val filter = CachedEventFilter(EventFilter(type = "stop"), mapOf("tenant" to jsonValue("acme")))
        val rule = CachedEventConsumption(ListenStrategy.ALL, listOf(filter, filter.copy(standard = EventFilter(type = "done"))))
        val original = eventListenStarted().let { it.copy(config = it.config.copy(until = UntilCondition.Event(rule))) }
        val encoded = WorkflowStateProtobufMapper.toProto(original)
        val decoded = WorkflowStateProtobufMapper.fromProto(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `legacy single termination filter remains wire compatible`() {
        val original = eventListenStarted().let {
            it.copy(config = it.config.copy(until = UntilCondition.Event(EventFilter(type = "stop"))))
        }
        val encoded = WorkflowStateProtobufMapper.toProto(original)
        assertEquals(original, WorkflowStateProtobufMapper.fromProto(encoded))
    }

    @Test
    fun `should round-trip all workflow state variants`() {
        val states = listOf(
            commandResumeFromTask(),
            commandResumeWithCompletedTask(),
            commandResumeWithFailedTask(),
            eventWorkflowCompleted(),
            eventWorkflowFailed(),
            eventForkBranchCompleted(),
            eventForkBranchFailed(),
            eventForEachCompleted(),
            eventTaskScheduled(),
            eventWaitStarted(),
            eventTaskRetryScheduled(),
            eventRunWorkflowStarted(),
            eventForkStarted(),
            eventListenStarted(),
            eventEmitStarted(),
            WorkflowEvent.CallA2AStarted(
                nodeStack(), jsonValue("card-input"), CallA2AConfig(
                    method = WithA2AMethod.TASKS_RESUBSCRIBE,
                    url = "https://example.org/card",
                    isCardResource = true,
                    parameters = buildJsonObject { put("id", "task") },
                    authentication = HttpAuthentication.Bearer("example"),
                ),
            ),
            eventCallHttpStarted(),
            eventRunScriptStarted(),
            eventRunShellStarted()
        )

        states.forEach { state ->
            val encoded = WorkflowStateProtobufMapper.toProto(state)
            val decoded = WorkflowStateProtobufMapper.fromProto(encoded)
            assertEquals(state, decoded)
        }
    }

    @Test
    fun `should preserve optional-null fields`() {
        val states = listOf<WorkflowState>(
            WorkflowCommand.ResumeFromTask(
                nodeStack = nodeStack(),
                nodePosition = taskPosition,
                rawInput = jsonValue("raw-input"),
                flowDirective = null
            ),
            WorkflowEvent.WorkflowFaulted(
                nodeStack = nodeStack(),
                rawInput = null,
                rawOutput = null,
                flowDirective = null,
                error = InternalException.Error(
                    type = "RuntimeError",
                    status = 500,
                    position = taskPosition.toString(),
                    title = null,
                    detail = null
                ),
                faultedAt = now
            )
        )

        states.forEach { state ->
            val encoded = WorkflowStateProtobufMapper.toProto(state)
            val decoded = WorkflowStateProtobufMapper.fromProto(encoded)
            assertEquals(state, decoded)
        }
    }

    @Test
    fun `should encode started event configs as structured protobuf fields`() {
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventWaitStarted()).wait_started?.config != null)
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventRunWorkflowStarted()).run_workflow_started?.config != null)
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventListenStarted()).listen_started?.config != null)
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventEmitStarted()).emit_started?.config != null)
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventCallHttpStarted()).call_http_started?.config != null)
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventRunScriptStarted()).run_script_started?.config != null)
        assertTrue(WorkflowStateProtobufMapper.toEventProto(eventRunShellStarted()).run_shell_started?.config != null)
    }

    private fun commandResumeFromTask(): WorkflowCommand.ResumeFromTask =
        WorkflowCommand.ResumeFromTask(
            nodeStack = nodeStack(),
            nodePosition = taskPosition,
            rawInput = jsonValue("raw-input"),
            flowDirective = FlowDirectiveGoto("next-task")
        )

    private fun commandResumeWithCompletedTask(): WorkflowCommand.ResumeWithCompletedTask =
        WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack(),
            rawOutput = jsonValue("raw-output")
        )

    private fun commandResumeWithFailedTask(): WorkflowCommand.ResumeWithFaultedTask =
        WorkflowCommand.ResumeWithFaultedTask(
            nodeStack = nodeStack(),
            error = sampleError()
        )

    private fun eventWorkflowCompleted(): WorkflowEvent.WorkflowCompleted =
        WorkflowEvent.WorkflowCompleted(
            output = jsonValue("ok"),
            completedAt = now,
            nodeStack = nodeStack()
        )

    private fun eventWorkflowFailed(): WorkflowEvent.WorkflowFaulted =
        WorkflowEvent.WorkflowFaulted(
            nodeStack = nodeStack(),
            rawInput = jsonValue("in"),
            rawOutput = jsonValue("out"),
            flowDirective = FlowDirectiveEnum.End,
            error = sampleError(),
            faultedAt = now
        )

    private fun eventForkBranchCompleted(): WorkflowEvent.ForkBranchCompleted =
        WorkflowEvent.ForkBranchCompleted(
            nodeStack = nodeStack(),
            branchPosition = NodePosition("/do/0/fork/1/branch"),
            branchOutput = jsonValue("branch-output"),
            completedAt = now
        )

    private fun eventForkBranchFailed(): WorkflowEvent.ForkBranchFaulted =
        WorkflowEvent.ForkBranchFaulted(
            nodeStack = nodeStack(),
            branchPosition = NodePosition("/do/0/fork/1/branch"),
            error = sampleError(),
            faultedAt = now
        )

    private fun eventForEachCompleted(): WorkflowEvent.ForEachCompleted =
        WorkflowEvent.ForEachCompleted(
            nodeStack = nodeStack(),
            output = jsonValue("foreach-output"),
        )

    private fun eventTaskScheduled(): WorkflowEvent.TaskScheduled =
        WorkflowEvent.TaskScheduled(
            nodeStack = nodeStack(),
            nodePosition = taskPosition,
            rawInput = jsonValue("task-input"),
            flowDirective = FlowDirectiveEnum.Continue
        )

    private fun eventWaitStarted(): WorkflowEvent.WaitStarted =
        WorkflowEvent.WaitStarted(
            nodeStack = nodeStack(),
            rawOutput = jsonValue("wait-output"),
            config = WaitConfig(waitUntil = now)
        )

    private fun eventTaskRetryScheduled(): WorkflowEvent.TaskRetryScheduled =
        WorkflowEvent.TaskRetryScheduled(
            nodeStack = nodeStack(),
            nodePosition = taskPosition,
            rawInput = jsonValue("retry-input"),
            flowDirective = FlowDirectiveEnum.Exit,
            retryAt = now
        )

    private fun eventRunWorkflowStarted(): WorkflowEvent.RunWorkflowStarted =
        WorkflowEvent.RunWorkflowStarted(
            nodeStack = nodeStack(),
            rawInput = jsonValue("child-input"),
            config = RunWorkflowConfig(
                namespace = WorkflowNamespace("ns"),
                name = WorkflowName("child"),
                version = WorkflowVersion("1.0.0"),
                input = jsonValue("input"),
                sync = true
            )
        )

    private fun eventForkStarted(): WorkflowEvent.ForkStarted =
        WorkflowEvent.ForkStarted(
            nodeStack = nodeStack(),
            rawInput = jsonValue("fork-input")
        )

    private fun eventListenStarted(): WorkflowEvent.ListenStarted =
        WorkflowEvent.ListenStarted(
            nodeStack = nodeStack(),
            rawOutput = jsonValue("listen-output"),
            config = ListenConfig(
                strategy = ListenStrategy.ANY,
                filters = listOf(
                    EventFilter(
                        type = "com.acme.event",
                        correlations = mapOf(
                            "orderId" to CorrelationDef(
                                from = "\${ .orderId }",
                                expect = "ORD-54321"
                            )
                        )
                    )
                ),
                until = UntilCondition.Expression(". | length > 0"),
                readAs = ListenAndReadAs.DATA,
                timeoutAt = now,
                correlationContext = buildJsonObject {
                    put("input", buildJsonObject {
                        put("orderId", "ORD-54321")
                    })
                }
            )
        )

    private fun eventEmitStarted(): WorkflowEvent.EmitStarted =
        WorkflowEvent.EmitStarted(
            nodeStack = nodeStack(),
            input = jsonValue("emit-input"),
            config = EmitConfig(
                id = "evt-id",
                source = "urn:test",
                type = "com.acme.created",
                data = jsonValue("payload")
            )
        )

    private fun eventCallHttpStarted(): WorkflowEvent.CallHttpStarted =
        WorkflowEvent.CallHttpStarted(
            nodeStack = nodeStack(),
            input = jsonValue("http-input"),
            config = CallHttpConfig(
                method = "GET",
                url = "https://example.org/health"
            )
        )

    private fun eventRunScriptStarted(): WorkflowEvent.RunScriptStarted =
        WorkflowEvent.RunScriptStarted(
            nodeStack = nodeStack(),
            input = jsonValue("script-input"),
            config = RunScriptConfig(
                language = "javascript",
                code = "return 1;"
            )
        )

    private fun eventRunShellStarted(): WorkflowEvent.RunShellStarted =
        WorkflowEvent.RunShellStarted(
            nodeStack = nodeStack(),
            input = jsonValue("shell-input"),
            config = RunShellConfig(
                command = "echo ok"
            )
        )

    private fun nodeStack(): NodeStack {
        val root = StackFrame(
            position = NodePosition.root,
            state = RootState(
                startedAt = now,
                workflowId = WorkflowId.random(),
                workflowInput = jsonValue("workflow-input")
            ),
            counter = 1
        )
        val task = StackFrame(
            position = taskPosition,
            state = TaskState(startedAt = now),
            counter = 2
        )
        return NodeStack(listOf(root, task))
    }

    private fun sampleError(): InternalException.Error =
        InternalException.Error(
            type = "RuntimeError",
            status = 500,
            position = taskPosition.toString(),
            title = "boom",
            detail = "stacktrace"
        )

    private companion object {
        private val now = Instant.fromEpochSeconds(1735732800)
        private val taskPosition = NodePosition("/do/0/task")
    }
}
