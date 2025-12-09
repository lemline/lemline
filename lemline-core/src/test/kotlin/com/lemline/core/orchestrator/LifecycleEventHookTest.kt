// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.InternalException
import com.lemline.core.getWorkflowToTest
import com.lemline.core.states.NodeStack
import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tests for lifecycle event hooks in StepByStepOrchestrator.
 *
 * These tests verify that lifecycle events are emitted at the correct points
 * during workflow execution. The tests use a capturing hook implementation
 * to verify event emission without requiring actual CloudEvent infrastructure.
 */
@ExperimentalTime
class LifecycleEventHookTest : FunSpec() {

    /**
     * Simple capturing hook that records all lifecycle events for verification.
     */
    private class CapturingLifecycleHook : LifecycleEventHook {
        val events = mutableListOf<String>()

        override suspend fun onWorkflowCreated(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
        ) {
            val workflowId = (nodeStack[NodePosition.root] as com.lemline.core.states.RootState).workflowId
            events.add("workflow.created:$workflowId")
        }

        override suspend fun onWorkflowStarted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            startedAt: Instant,
        ) {
            val workflowId = (nodeStack[NodePosition.root] as com.lemline.core.states.RootState).workflowId
            events.add("workflow.started:$workflowId")
        }

        override suspend fun onWorkflowCompleted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            output: JsonElement,
            completedAt: Instant,
        ) {
            val workflowId = (nodeStack[NodePosition.root] as com.lemline.core.states.RootState).workflowId
            events.add("workflow.completed:$workflowId")
        }

        override suspend fun onWorkflowFaulted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            error: InternalException.Error,
            failedAt: Instant,
        ) {
            val workflowId = (nodeStack[NodePosition.root] as com.lemline.core.states.RootState).workflowId
            events.add("workflow.faulted:$workflowId")
        }

        override suspend fun onTaskCreated(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            rawInput: JsonElement,
            createdAt: Instant,
        ) {
            events.add("task.created:$nodePosition")
        }

        override suspend fun onTaskStarted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            rawInput: JsonElement,
            startedAt: Instant,
        ) {
            events.add("task.started:$nodePosition")
        }

        override suspend fun onTaskCompleted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            rawOutput: JsonElement,
            completedAt: Instant,
        ) {
            events.add("task.completed:$nodePosition")
        }

        override suspend fun onTaskFaulted(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            error: InternalException.Error,
            failedAt: Instant,
        ) {
            events.add("task.faulted:$nodePosition")
        }

        override suspend fun onTaskRetried(
            workflowInfo: WorkflowInfo,
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            retryAt: Instant,
            attemptNumber: Int,
        ) {
            events.add("task.retried:$nodePosition:attempt=$attemptNumber")
        }

        fun clear() = events.clear()
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

        val startState = StepByStepOrchestrator.initCmd(
            workflowId = WorkflowId.random(),
            workflowInput = input,
            hasWaitingParent = false,
            startedAt = Clock.System.now()
        )

        val outcome = FullOrchestrator.resume(
            workflow = workflow,
            command = startState,
            serde = true,
            lifecycleHook = lifecycleHook,
        )

        return outcome.value()
    }

    init {
        afterEach {
            DefinitionCache.clear()
        }

        // ========================================
        // Workflow Lifecycle Events
        // ========================================

        test("should emit workflow.started on first task") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - setValue:
                      set:
                        result: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            assertTrue(hook.events.any { it.startsWith("workflow.started:") })
        }

        test("should emit workflow.completed on successful completion") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - setValue:
                      set:
                        result: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            assertTrue(hook.events.any { it.startsWith("workflow.completed:") })
        }

        test("should emit workflow.faulted on unhandled error") {
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

            assertTrue(hook.events.any { it.startsWith("workflow.faulted:") })
        }

        // ========================================
        // Task Lifecycle Events
        // ========================================

        test("should emit task.started for each task") {
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

            // Verify task.started events for both tasks
            assertTrue(hook.events.any { it == "task.started:/do/step1" })
            assertTrue(hook.events.any { it == "task.started:/do/step2" })
        }

        test("should emit task.created for scheduled activities") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - step1:
                      set:
                        a: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // TaskScheduled produces task.created
            assertTrue(hook.events.any { it.startsWith("task.created:") })
        }

        // ========================================
        // Event Order Verification
        // ========================================

        test("should emit events in correct order for simple workflow") {
            val hook = CapturingLifecycleHook()
            val yaml = """
                do:
                  - setValue:
                      set:
                        result: 1
            """

            executeWorkflow(yaml, buildJsonObject { }, hook)

            // Verify order: workflow.started comes first, workflow.completed comes last
            val workflowStartedIndex = hook.events.indexOfFirst { it.startsWith("workflow.started:") }
            val workflowCompletedIndex = hook.events.indexOfFirst { it.startsWith("workflow.completed:") }

            assertTrue(workflowStartedIndex >= 0, "workflow.started should be emitted")
            assertTrue(workflowCompletedIndex >= 0, "workflow.completed should be emitted")
            assertTrue(workflowStartedIndex < workflowCompletedIndex, "workflow.started should come before workflow.completed")
        }

        // ========================================
        // NOOP Hook
        // ========================================

        test("should work with NOOP hook (no errors)") {
            val yaml = """
                do:
                  - setValue:
                      set:
                        result: 1
            """

            // Using NOOP hook should not throw any errors
            val output = executeWorkflow(yaml, buildJsonObject { }, LifecycleEventHook.NOOP)

            assertEquals(1, (output as JsonObject)["result"]?.toString()?.toInt())
        }
    }
}
