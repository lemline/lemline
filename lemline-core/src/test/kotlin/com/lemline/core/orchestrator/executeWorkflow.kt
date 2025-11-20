// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.orchestrator

import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.getWorkflowToTest
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.RootState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

internal suspend fun executeContinuousWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    val workflow = getWorkflowToTest(yaml, namespace, name, version)

    val startState = WorkflowOrchestrator.initState(
        workflowId = WorkflowId.random(),
        workflowInput = input,
        hasWaitingParent = false,
        startedAt = Clock.System.now()
    )

    val state = WorkflowOrchestrator.resume(
        workflow = workflow,
        state = startState,
        executionMode = ExecutionMode.CONTINUOUS
    )

    return when (state) {
        is WorkflowEvent.WorkflowCompleted -> state.output
        is WorkflowEvent.TaskFailed -> throw state.exception

        else -> throw IllegalStateException("Unexpected state: ${state::class.simpleName}")
    }
}

internal suspend fun executeTaskByTaskWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    val workflow = getWorkflowToTest(yaml, namespace, name, version)

    return runUntilComplete(
        workflow = workflow,
        input = input,
        hasWaitingParent = false,
        executionMode = ExecutionMode.TASK_BY_TASK
    )
}

internal suspend fun executeActivityByActivityWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    val workflow = getWorkflowToTest(yaml, namespace, name, version)

    return runUntilComplete(
        workflow = workflow,
        input = input,
        hasWaitingParent = false,
        executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
    )
}

// Public entry point
private suspend fun runUntilComplete(
    workflow: Workflow,
    input: JsonElement,
    hasWaitingParent: Boolean,
    executionMode: ExecutionMode
): JsonElement {

    val startState = WorkflowOrchestrator.initState(
        workflowId = WorkflowId.random(),
        workflowInput = input,
        hasWaitingParent = hasWaitingParent,
        startedAt = Clock.System.now()
    )

    return runWorkflowStep(
        workflow = workflow,
        state = startState,
        executionMode = executionMode,
    )
}

// Stateless, reusable helper
private tailrec suspend fun runWorkflowStep(
    workflow: Workflow,
    state: WorkflowCommand,
    executionMode: ExecutionMode,
): JsonElement {

    val next = WorkflowOrchestrator.resume(
        workflow = workflow,
        state = state,
        executionMode = executionMode
    )

    val jsonString = next.toJsonString()

    return when (val workflowEvent = WorkflowEvent.fromJsonString(jsonString)) {
        is WorkflowEvent.WorkflowCompleted -> workflowEvent.output
        is WorkflowEvent.TaskFailed -> throw workflowEvent.exception
        is WorkflowEvent.ForkBranchCompleted -> workflowEvent.output

        is WorkflowEvent.ForkStarted -> forkStarted(workflow, workflowEvent, executionMode)
        is WorkflowEvent.RetryScheduled -> runWorkflowStep(workflow, workflowEvent.resume(), executionMode)
        is WorkflowEvent.TaskScheduled -> runWorkflowStep(workflow, workflowEvent.resume(), executionMode)
        is WorkflowEvent.WaitStarted -> runWorkflowStep(workflow, workflowEvent.resume(), executionMode)
        is WorkflowEvent.RunWorkflowStarted -> runWorkflowStarted(workflow, workflowEvent, executionMode)
    }
}

private suspend fun runWorkflowStarted(
    workflow: Workflow,
    workflowState: WorkflowEvent.RunWorkflowStarted,
    executionMode: ExecutionMode
): JsonElement {
    val enrichedState = if (workflowState.childConfig.sync) {
        val childWorkflow = DefinitionCache.getWorkflow(
            namespace = workflowState.childConfig.namespace,
            name = workflowState.childConfig.name,
            version = workflowState.childConfig.version
        )
            ?: throw IllegalStateException("Child workflow not found: ${workflowState.childConfig.namespace}/${workflowState.childConfig.name}/${workflowState.childConfig.version}")
        val rawOutput = runUntilComplete(
            workflow = childWorkflow,
            input = workflowState.childConfig.input,
            hasWaitingParent = true,
            executionMode = executionMode
        )
        workflowState.resumeSync(rawOutput = rawOutput)
    } else {
        // await=false (fire-and-forget)
        workflowState.resumeAsync()
    }

    return runWorkflowStep(workflow, enrichedState, executionMode)
}

private suspend fun forkStarted(
    workflow: Workflow,
    workflowState: WorkflowEvent.ForkStarted,
    executionMode: ExecutionMode
): JsonElement {
    @Suppress("UNCHECKED_CAST")
    val forkNode = workflow.getNode(workflowState.nodePosition) as Node<ForkTask>
    (workflowState.taskStates[NodePosition.root] as RootState).workflowId

    // launch all branches in parallel
    val results = coroutineScope {
        forkNode.children!!.mapIndexed { index, branchNode ->
            async {
                val output = runWorkflowStep(workflow, workflowState.startBranch(branchNode.position), executionMode)
                index to output
            }
        }
    }

    val output = if (forkNode.task.fork.isCompete) {
        // returns the array of all branches
        JsonArray(coroutineScope {
            results.awaitAll().map { it.second }
        })
    } else {
        // returns the winner branch
        select {
            results.forEach { deferred ->
                deferred.onAwait { it }
            }
        }.second
    }

    return runWorkflowStep(workflow, workflowState.resume(output), executionMode)
}
