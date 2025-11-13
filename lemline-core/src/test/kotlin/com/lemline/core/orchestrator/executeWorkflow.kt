@file:OptIn(ExperimentalTime::class)

package com.lemline.core.orchestrator

import com.lemline.core.getWorkflowNode
import com.lemline.core.nodes.Node
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

internal suspend fun executeContinuousWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String,
    name: String,
    version: String
): JsonElement {
    val rootNode = getWorkflowNode(yaml, namespace, name, version)
    val result = WorkflowOrchestrator.resumeFromTask(
        nextNode = rootNode,
        nextRawInput = input,
        executionMode = ExecutionMode.CONTINUOUS
    )
    return when (result) {
        is WorkflowResult.WorkflowCompleted -> result.output
        is WorkflowResult.WorkflowFailed -> throw result.error
        else -> throw IllegalStateException("Unexpected output type: $result")
    }
}

internal suspend fun executeTaskByTaskWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String,
    name: String,
    version: String
): JsonElement {
    val rootNode = getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(
        rootNode = rootNode,
        input = input,
        executionMode = ExecutionMode.TASK_BY_TASK
    )
}

internal suspend fun executeActivityByActivityWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String,
    name: String,
    version: String
): JsonElement {
    val rootNode = getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(
        rootNode = rootNode,
        input = input,
        executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
    )
}

/**
 * Execute a workflow from start to completion by looping through pause results.
 *
 * This method simulates what the distributed runner does:
 * 1. Execute workflow until it pauses
 * 2. Handle the pause reason (delay, child workflow, etc.)
 * 3. Resume execution
 * 4. Repeat until workflow completes
 */
private suspend fun runUntilComplete(
    rootNode: Node<*>,
    input: JsonElement,
    executionMode: ExecutionMode
): JsonElement {
    var result = WorkflowOrchestrator.resumeFromTask(
        nextNode = rootNode,
        nextRawInput = input,
        executionMode = executionMode
    )

    while (true) {
        when (result) {
            is WorkflowResult.WorkflowCompleted -> {
                return result.output
            }

            is WorkflowResult.WorkflowFailed -> {
                throw result.error
            }

            is WorkflowResult.NextTask -> {
                // Continue execution from the next node
                result = WorkflowOrchestrator.resumeFromTask(
                    states = result.states.toMutableMap(),
                    nextNode = result.nextNode,
                    nextRawInput = result.nextRawInput,
                    nextFlowDirective = result.nextFlowDirective,
                    executionMode = executionMode
                )
            }

            is WorkflowResult.ExecuteWait -> {
                delay(result.duration)

                // Continue execution from next node with the dataset from wait
                result = WorkflowOrchestrator.resumeFromInterruptedTask(
                    states = result.states.toMutableMap(),
                    node = result.node,
                    rawOutput = result.rawOutput,
                    executionMode = executionMode
                )
            }

            is WorkflowResult.RetryTask -> {
                delay(result.duration)

                // Continue execution after retry delay - currentDataset unchanged
                result = WorkflowOrchestrator.resumeFromTask(
                    states = result.states.toMutableMap(),
                    nextNode = result.node,
                    nextRawInput = result.rawInput,
                    nextFlowDirective = result.flowDirective,
                    executionMode = executionMode
                )
            }

            is WorkflowResult.ExecuteRunWorkflow -> {
                // Execute child workflow recursively
                val childNode = WorkflowOrchestrator.getRootNodeFromWorkflow(
                    result.childConfig.namespace,
                    result.childConfig.name,
                    result.childConfig.version
                )
                val childOutput = if (result.childConfig.awaitCompletion) {
                    // await=true: Execute child and wait for output
                    runUntilComplete(
                        rootNode = childNode,
                        input = result.childConfig.rawInput,
                        executionMode = ExecutionMode.CONTINUOUS
                    )
                } else {
                    // await=false (fire-and-forget): Use transformed input as output
                    result.transformedInput!!
                }

                // Resume parent with child output
                result = WorkflowOrchestrator.resumeFromInterruptedTask(
                    states = result.states.toMutableMap(),
                    node = result.node,
                    rawOutput = childOutput,
                    executionMode = executionMode
                )
            }
        }
    }
}
