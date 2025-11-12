package com.lemline.core.execution

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.getWorkflowNode
import com.lemline.core.nodes.Node
import com.lemline.core.states.MutableStates
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

@ExperimentalTime
internal suspend fun executeContinuousWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String,
    name: String,
    version: String
): JsonElement {
    val rootNode = getWorkflowNode(yaml, namespace, name, version)
    val result = WorkflowOrchestrator.resumeFromTask(
        rootNode,
        input,
        mutableMapOf(),
        ExecutionMode.CONTINUOUS
    )
    return when (result) {
        is WorkflowResult.WorkflowCompleted -> result.output
        is WorkflowResult.WorkflowFailed -> throw result.error
        else -> throw IllegalStateException("Unexpected output type: $result")
    }
}

@ExperimentalTime
internal suspend fun executeTaskByTaskWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String,
    name: String,
    version: String
): JsonElement {
    val rootNode = getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(rootNode, input, mutableMapOf(), ExecutionMode.TASK_BY_TASK)
}

@ExperimentalTime
internal suspend fun executeActivityByActivityWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String,
    name: String,
    version: String
): JsonElement {
    val rootNode = getWorkflowNode(yaml, namespace, name, version)
    return runUntilComplete(rootNode, input, mutableMapOf(), ExecutionMode.ACTIVITY_BY_ACTIVITY)
}

/**
 * Execute a workflow from start to completion by looping through pause results.
 *
 * This method simulates what the distributed runner does:
 * 1. Execute workflow until it pauses
 * 2. Handle the pause reason (delay, child workflow, etc.)
 * 3. Resume execution
 * 4. Repeat until workflow completes
 *
 * @param rootNode The root node of the workflow to execute
 * @param input The initial input dataset
 * @param states The initial states (default: empty)
 * @return The final workflow output
 */
@ExperimentalTime
suspend fun runUntilComplete(
    rootNode: Node<*>,
    input: JsonElement,
    states: MutableStates = mutableMapOf(),
    mode: ExecutionMode
): JsonElement {
    var result = WorkflowOrchestrator.resumeFromTask(rootNode, input, states, mode)

    while (true) {
        when (result) {
            is WorkflowResult.WorkflowCompleted -> {
                return result.output
            }

            is WorkflowResult.WorkflowFailed -> {
                throw result.error
            }

            is WorkflowResult.TaskCompleted -> {
                // Continue execution from the next node
                result = WorkflowOrchestrator.resumeFromTask(
                    node = result.nextNode,
                    input = result.output,
                    states = result.states.toMutableMap(),
                    mode = mode
                )
            }

            is WorkflowResult.Wait -> {
                // delay(result.duration)

                // Continue execution from next node with the dataset from wait
                result = WorkflowOrchestrator.resumeFromInterruptedTask(
                    node = result.node,
                    rawOutput = result.rawOutput,
                    states = result.states.toMutableMap(),
                    mode = mode
                )
            }

            is WorkflowResult.Retry -> {
                // delay(result.duration)

                // Continue execution after retry delay - currentDataset unchanged
                result = WorkflowOrchestrator.resumeFromTask(
                    node = result.node,
                    input = result.rawInput,
                    states = result.states.toMutableMap(),
                    mode = mode
                )
            }

            is WorkflowResult.RunWorkflow -> {
                // Execute child workflow recursively
                val childNode = resolveChildWorkflow(result.childConfig)
                val childOutput = if (result.childConfig.awaitCompletion) {
                    // await=true: Execute child and wait for output
                    runUntilComplete(childNode, result.childConfig.rawInput, mutableMapOf(), ExecutionMode.CONTINUOUS)
                } else {
                    // await=false (fire-and-forget): Use transformed input as output
                    result.transformedInput!!
                }

                // Resume parent with child output
                result = WorkflowOrchestrator.resumeFromInterruptedTask(
                    node = result.node,
                    rawOutput = childOutput,
                    states = result.states.toMutableMap(),
                    mode = mode
                )
            }
        }
    }
}

/**
 * Resolve a child workflow definition from the cache.
 */
private fun resolveChildWorkflow(config: ChildWorkflowException.Config): Node<*> {
    val namespace = WorkflowNamespace(config.namespace)
    val name = WorkflowName(config.name)
    val version = WorkflowVersion(config.version)

    val definition = DefinitionCache.getOrNull(namespace, name, version)
        ?: throw IllegalStateException(
            "Child workflow not found: namespace=$namespace, name=$name, version=$version"
        )

    return DefinitionCache.getRootNode(definition)
}
