// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.orchestrator

import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.getWorkflowToTest
import com.lemline.core.states.WorkflowState
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

internal suspend fun executeContinuousWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    val workflow = getWorkflowToTest(yaml, namespace, name, version)

    val starting = WorkflowState.Starting(
        input = input,
        startedAt = Clock.System.now()
    )

    val state = WorkflowOrchestrator.resume(
        workflow = workflow,
        state = starting,
        executionMode = ExecutionMode.CONTINUOUS
    )

    return when (state) {
        is WorkflowState.Completed ->
            state.output

        is WorkflowState.Failed ->
            throw state.exception ?: IllegalStateException("Workflow failed without explicit exception: $state")

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
        executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
    )
}

// Public entry point
private suspend fun runUntilComplete(
    workflow: Workflow,
    input: JsonElement,
    executionMode: ExecutionMode
): JsonElement {

    val starting = WorkflowState.Starting(
        input = input,
        startedAt = Clock.System.now()
    )

    return runWorkflowStep(
        workflow = workflow,
        jsonString = starting.toJsonString(),
        executionMode = executionMode,
    )
}

// Stateless, reusable helper
private tailrec suspend fun runWorkflowStep(
    workflow: Workflow,
    jsonString: String,
    executionMode: ExecutionMode,
): JsonElement = when (val state = WorkflowState.fromJsonString(jsonString)) {
    is WorkflowState.Completed ->
        state.output

    is WorkflowState.Failed ->
        throw state.exception ?: IllegalStateException("Workflow failed without explicit exception: $state")

    is WorkflowState.RunningChildWorkflow -> {
        val enrichedState =
            if (state.childConfig.sync) {
                val childWorkflow = DefinitionCache.getWorkflow(
                    namespace = state.childConfig.namespace,
                    name = state.childConfig.name,
                    version = state.childConfig.version
                )
                    ?: throw IllegalStateException("Child workflow not found: ${state.childConfig.namespace}/${state.childConfig.name}/${state.childConfig.version}")
                val rawOutput = runUntilComplete(
                    workflow = childWorkflow,
                    input = state.childConfig.input,
                    executionMode = ExecutionMode.CONTINUOUS
                )
                state.copy(rawOutput = rawOutput)
            } else {
                // await=false (fire-and-forget): rawOutput is already set in state
                state
            }

        val next = WorkflowOrchestrator.resume(
            workflow = workflow,
            state = enrichedState,
            executionMode = executionMode
        )
        runWorkflowStep(workflow, next.toJsonString(), executionMode)
    }

    else -> {
        val next = WorkflowOrchestrator.resume(
            workflow = workflow,
            state = state,
            executionMode = executionMode
        )

        runWorkflowStep(workflow, next.toJsonString(), executionMode)
    }
}

