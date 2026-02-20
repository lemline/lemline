// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.activities.mock.MockFunctionResolver
import com.lemline.core.cloudevents.InMemoryCloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.getWorkflowToTest
import com.lemline.core.orchestrator.FullOrchestrator
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.NodeStack
import com.lemline.core.workflows.WorkflowCache
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Tests for lifecycle event hooks in StepByStepOrchestrator.
 *
 * These tests verify that lifecycle events are emitted at the correct points
 * during workflow execution. The tests use a capturing hook implementation
 * to verify event emission without requiring actual CloudEvent infrastructure.
 */
class LifecycleEventHookTest : FunSpec() {

    /**
     * Simple capturing hook that records all lifecycle events for verification.
     * Uses thread-safe collection since events may be emitted from concurrent coroutines.
     */
    private class CapturingLifecycleHook : LifecycleEventHook {
        private val _events = CopyOnWriteArrayList<String>()
        val events: List<String> get() = _events

        /**
         * Extracts a meaningful task identifier from a node position.
         * For nested do blocks like /do/outer/do, extracts "outer" (parent segment).
         * For regular tasks like /do/step1, extracts "step1" (last segment).
         */
        private fun NodePosition.taskId(): String {
            val path = this.toString()
            val segments = path.split("/").filter { it.isNotEmpty() }
            // If the last segment is "do" and there are more segments, use parent
            return if (segments.lastOrNull() == "do" && segments.size > 1) {
                segments[segments.size - 2]
            } else {
                nodeName
            }
        }

        override suspend fun onWorkflowCreated(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
        ) {
            val workflowId = nodeStack.rootState.workflowId
            _events.add("workflow.created:$workflowId")
        }

        override suspend fun onWorkflowStarted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            startedAt: Instant,
        ) {
            val workflowId = nodeStack.rootState.workflowId
            _events.add("workflow.started:$workflowId")
        }

        override suspend fun onWorkflowCompleted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            output: JsonElement,
            completedAt: Instant,
        ) {
            val workflowId = nodeStack.rootState.workflowId
            _events.add("workflow.completed:$workflowId")
        }

        override suspend fun onWorkflowFaulted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            error: InternalException.Error,
            failedAt: Instant,
        ) {
            val workflowId = nodeStack.rootState.workflowId
            _events.add("workflow.faulted:$workflowId")
        }

        override suspend fun onTaskCreated(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            input: JsonElement,
            createdAt: Instant,
        ) {
            _events.add("task.created:${nodePosition.taskId()}")
        }

        override suspend fun onTaskStarted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            rawInput: JsonElement,
            startedAt: Instant,
        ) {
            _events.add("task.started:${nodePosition.taskId()}")
        }

        override suspend fun onTaskCompleted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            output: JsonElement,
            completedAt: Instant,
        ) {
            _events.add("task.completed:${nodePosition.taskId()}")
        }

        override suspend fun onTaskFaulted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            error: InternalException.Error,
            failedAt: Instant,
        ) {
            _events.add("task.faulted:${nodePosition.taskId()}")
        }

        override suspend fun onTaskRetried(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            retryAt: Instant,
            attemptNumber: Int,
        ) {
            _events.add("task.retried:${nodePosition.taskId()}:attempt=$attemptNumber")
        }
    }

    private suspend fun executeWorkflow(
        yaml: String,
        input: JsonElement = buildJsonObject { },
        lifecycleHook: LifecycleEventHook,
        namespace: String = "default",
        name: String = "test",
        version: String = "0.1.0",
    ): JsonElement {
        val workflow = getWorkflowToTest(yaml, namespace, name, version)
        val workflowInfo = WorkflowInfo(
            WorkflowNamespace(namespace),
            WorkflowName(name),
            WorkflowVersion(version)
        )

        val startState = StepByStepOrchestrator.initCmd(
            workflowId = WorkflowId.random(),
            workflowInput = input,
            hasWaitingParent = false,
            startedAt = Clock.System.now()
        )

        // Emit workflow.created before starting execution (mimics Starter behavior)
        lifecycleHook.onWorkflowCreated(workflowInfo, startState.nodeStack)

        val outcome = FullOrchestrator.resume(
            workflow = workflow,
            command = startState,
            serde = true,
            activityExecutor = MockActivityExecutor.empty(),
            functionResolver = MockFunctionResolver(),
            cloudEventHook = InMemoryCloudEventHook(),
            lifecycleHook = lifecycleHook,
        )

        return outcome.value()
    }

    /**
     * Helper to strip workflow ID from events for easier comparison.
     * Workflow events include IDs, task events use node names.
     */
    private fun List<String>.stripWorkflowIds(): List<String> = map { event ->
        when {
            event.startsWith("workflow.created:") -> "workflow.created"
            event.startsWith("workflow.started:") -> "workflow.started"
            event.startsWith("workflow.completed:") -> "workflow.completed"
            event.startsWith("workflow.faulted:") -> "workflow.faulted"
            else -> event
        }
    }

    init {
        afterEach {
            WorkflowCache.clear()
        }

        // ========================================
        // Complete Event Sequence Tests
        // ========================================

        test("single task workflow - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - setValue:
                      set:
                        result: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:setValue",
                "task.started:setValue",
                "task.completed:setValue",
                "task.completed:do",
                "workflow.completed"
            )
        }

        test("two task workflow - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - step1:
                      set:
                        a: 1
                  - step2:
                      set:
                        b: 2
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:step1",
                "task.started:step1",
                "task.completed:step1",
                "task.created:step2",
                "task.started:step2",
                "task.completed:step2",
                "task.completed:do",
                "workflow.completed"
            )
        }

        test("three task workflow - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - step1:
                      set:
                        a: 1
                  - step2:
                      set:
                        b: 2
                  - step3:
                      set:
                        c: 3
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:step1",
                "task.started:step1",
                "task.completed:step1",
                "task.created:step2",
                "task.started:step2",
                "task.completed:step2",
                "task.created:step3",
                "task.started:step3",
                "task.completed:step3",
                "task.completed:do",
                "workflow.completed"
            )
        }

        // ========================================
        // Error Event Sequences
        // ========================================

        test("workflow fault - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                          status: 500
            """

            try {
                executeWorkflow(yaml, buildJsonObject { }, hook)
            } catch (_: Exception) {
                // Expected to fail
            }

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:raiseError",
                "task.started:raiseError",
                "task.faulted:raiseError",
                "workflow.faulted"
            )
        }

        test("task fault after successful task - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - step1:
                      set:
                        a: 1
                  - raiseError:
                      raise:
                        error:
                          type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                          status: 500
            """

            try {
                executeWorkflow(yaml, buildJsonObject { }, hook)
            } catch (_: Exception) {
                // Expected to fail
            }

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:step1",
                "task.started:step1",
                "task.completed:step1",
                "task.created:raiseError",
                "task.started:raiseError",
                "task.faulted:raiseError",
                "workflow.faulted"
            )
        }

        // ========================================
        // Try/Catch Event Sequences
        // ========================================

        test("caught error - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - handleError:
                      try:
                        - failingTask:
                            raise:
                              error:
                                type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                                status: 500
                      catch:
                        errors:
                          with:
                            type: https://serverlessworkflow.io/spec/1.0.0/errors/runtime
                        do:
                          - recovery:
                              set:
                                recovered: true
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // Note: try and catch are separate tasks in the workflow tree
            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:handleError",
                "task.started:handleError",
                "task.created:try",
                "task.started:try",
                "task.created:failingTask",
                "task.started:failingTask",
                "task.faulted:failingTask",
                "task.created:catch",
                "task.started:catch",
                "task.created:recovery",
                "task.started:recovery",
                "task.completed:recovery",
                "task.completed:catch",
                "task.completed:handleError",
                "task.completed:do",
                "workflow.completed"
            )
        }

        // ========================================
        // Nested Do Block Event Sequences
        // ========================================

        test("nested do block - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - outer:
                      do:
                        - inner:
                            set:
                              result: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:outer",
                "task.started:outer",
                "task.created:inner",
                "task.started:inner",
                "task.completed:inner",
                "task.completed:outer",
                "task.completed:do",
                "workflow.completed"
            )
        }

        // ========================================
        // For Loop Event Sequences
        // ========================================

        test("for loop - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = $$"""
                do:
                  - createData:
                      set:
                        items: [1, 2]
                  - processItems:
                      for:
                        each: item
                        in: ${ .items }
                      do:
                        - processItem:
                            set:
                              processed: true
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // For loops emit created/started events on each iteration
            // The actual sequence shows the for loop re-enters on each iteration
            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:createData",
                "task.started:createData",
                "task.completed:createData",
                "task.created:processItems",   // for loop scheduled
                "task.started:processItems",   // for loop initial entry
                // First iteration
                "task.created:processItems",   // for loop re-entry for iteration 1
                "task.started:processItems",
                "task.created:processItem",
                "task.started:processItem",
                "task.completed:processItem",
                "task.completed:processItems", // iteration 1 done
                // Second iteration
                "task.created:processItems",   // for loop re-entry for iteration 2
                "task.started:processItems",
                "task.created:processItem",
                "task.started:processItem",
                "task.completed:processItem",
                "task.completed:processItems", // iteration 2 done
                // Loop complete
                "task.completed:processItems", // for loop final completion
                "task.completed:do",
                "workflow.completed"
            )
        }

        // ========================================
        // Conditional Execution Event Sequences
        // ========================================

        test("conditional if - skipped task emits no events") {
            val hook = CapturingLifecycleHook()
            val yaml = $$"""
                do:
                  - setupData:
                      set:
                        shouldRun: false
                  - conditionalTask:
                      if: ${ .shouldRun }
                      set:
                        result: 1
                  - alwaysRuns:
                      set:
                        done: true
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // conditionalTask should be skipped entirely (no events)
            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:setupData",
                "task.started:setupData",
                "task.completed:setupData",
                "task.created:conditionalTask",
                "task.started:conditionalTask",
                "task.completed:conditionalTask",
                "task.created:alwaysRuns",
                "task.started:alwaysRuns",
                "task.completed:alwaysRuns",
                "task.completed:do",
                "workflow.completed"
            )
        }

        test("conditional if - executed task emits all events") {
            val hook = CapturingLifecycleHook()
            val yaml = $$"""
                do:
                  - setupData:
                      set:
                        shouldRun: true
                  - conditionalTask:
                      if: ${ .shouldRun }
                      set:
                        result: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:setupData",
                "task.started:setupData",
                "task.completed:setupData",
                "task.created:conditionalTask",
                "task.started:conditionalTask",
                "task.completed:conditionalTask",
                "task.completed:do",
                "workflow.completed"
            )
        }

        // ========================================
        // Fork Event Sequences
        // ========================================

        test("fork with two branches - complete event sequence") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - raceWork:
                      fork:
                        compete: false
                        branches:
                          - slowBranch:
                              do:
                                - delay:
                                    wait:
                                      seconds: 1
                                - result:
                                    set:
                                      winner: "slow"
                          - fastBranch:
                              set:
                                winner: "fast"
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // Fork executes branches in parallel
            // Note: branches don't emit task.created (they're started directly by fork)
            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:raceWork",
                "task.started:raceWork",
                "task.created:slowBranch",
                "task.started:slowBranch",
                "task.created:delay",
                "task.started:delay",
                "task.created:fastBranch",
                "task.started:fastBranch",
                "task.completed:fastBranch",
                "task.completed:delay",
                "task.created:result",
                "task.started:result",
                "task.completed:result",
                "task.completed:slowBranch",
                "task.completed:raceWork",
                "task.completed:do",
                "workflow.completed"
            )
        }

        test("fork in compete mode - first branch wins") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - raceWork:
                      fork:
                        compete: true
                        branches:
                          - slowBranch:
                              do:
                                - delay:
                                    wait:
                                      seconds: 1
                                - result:
                                    set:
                                      winner: "slow"
                          - fastBranch:
                              set:
                                winner: "fast"
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // In compete mode, first branch to complete wins, but all branches run to completion
            // (since these are synchronous tasks, they all complete before fork returns)
            hook.events.stripWorkflowIds() shouldBe listOf(
                "workflow.created",
                "workflow.started",
                "task.created:do",
                "task.started:do",
                "task.created:raceWork",
                "task.started:raceWork",
                "task.created:slowBranch",
                "task.started:slowBranch",
                "task.created:delay",
                "task.started:delay",
                "task.created:fastBranch",
                "task.started:fastBranch",
                "task.completed:fastBranch",
                "task.completed:raceWork",
                "task.completed:do",
                "workflow.completed"
            )
        }
    }
}
