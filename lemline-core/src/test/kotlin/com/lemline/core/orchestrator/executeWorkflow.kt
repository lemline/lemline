// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.orchestrator

import com.lemline.core.getWorkflowNode
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

internal suspend fun executeContinuousWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(namespace, name, version, input, ExecutionMode.CONTINUOUS)
}


internal suspend fun executeTaskByTaskWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(namespace, name, version, input, ExecutionMode.TASK_BY_TASK)
}

internal suspend fun executeActivityByActivityWorkflow(
    yaml: String,
    namespace: String,
    name: String,
    version: String,
    input: JsonElement
): JsonElement {
    getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(namespace, name, version, input, ExecutionMode.ACTIVITY_BY_ACTIVITY)
}

// Public entry point
internal suspend fun runUntilComplete(
    namespace: String,
    name: String,
    version: String,
    input: JsonElement,
    executionMode: ExecutionMode
): JsonElement {
    val initial = WorkflowOrchestrator.start(
        namespace = namespace,
        name = name,
        version = version,
        input = input,
        executionMode = executionMode
    )
    return runWorkflowStep(
        namespace = namespace,
        name = name,
        version = version,
        executionMode = executionMode,
        state = initial,
    )
}

// Stateless, reusable helper
internal tailrec suspend fun runWorkflowStep(
    namespace: String,
    name: String,
    version: String,
    executionMode: ExecutionMode,
    state: WorkflowState,
): JsonElement = when (state) {
    is WorkflowState.Completed ->
        state.output

    is WorkflowState.Failed ->
        throw state.exception ?: IllegalStateException("Workflow failed without explicit exception: $state")

    is WorkflowState.RunningChildWorkflow -> {
        val enrichedState =
            if (state.childConfig.sync) {
                val rawOutput = runUntilComplete(
                    namespace = state.childConfig.namespace,
                    name = state.childConfig.name,
                    version = state.childConfig.version,
                    input = state.childConfig.input,
                    executionMode = ExecutionMode.CONTINUOUS
                )
                state.copy(rawOutput = rawOutput)
            } else {
                // await=false (fire-and-forget): rawOutput is already set in state
                state
            }

        val next = WorkflowOrchestrator.resume(
            namespace = namespace,
            name = name,
            version = version,
            state = enrichedState,
            executionMode = executionMode
        )
        runWorkflowStep(namespace, name, version, executionMode, next)
    }

    else -> {
        val next = WorkflowOrchestrator.resume(
            namespace = namespace,
            name = name,
            version = version,
            state = state,
            executionMode = executionMode
        )
        runWorkflowStep(namespace, name, version, executionMode, next)
    }
}
