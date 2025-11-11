// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.execution.pausable

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.ChildWorkflowConfig
import com.lemline.core.nodes.Node
import com.lemline.core.states.MutableStates
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Test helper for PausableOrchestrator that simulates distributed execution locally.
 *
 * This helper loops through pausable results until workflow completion, handling:
 * - ActivityCompleted: Continue execution from next step
 * - WaitNeeded: Simulate delay and continue
 * - RetryNeeded: Simulate retry delay and continue
 * - SubWorkflowNeeded: Execute child workflow recursively
 *
 * This allows PausableOrchestrator tests to verify the same final outputs as
 * CompleteOrchestrator tests while still testing pause behavior separately.
 */
@ExperimentalTime
object PausableOrchestratorTestHelper {

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
    suspend fun runUntilComplete(
        rootNode: Node<*>,
        input: JsonElement,
        states: MutableStates = mutableMapOf()
    ): JsonElement {
        var result = PausableOrchestrator.run(rootNode, input, states)

        /**
         * Finds a node in the tree hierarchy based on its unique reference.
         */
        fun findNodeByReference(reference: String, root: Node<*> = rootNode): Node<*> {
            if (root.reference == reference) return root

            root.children?.forEach { child ->
                try {
                    return findNodeByReference(reference, child)
                } catch (_: IllegalStateException) {
                    // Do nothing - continue searching for other children
                }
            }

            throw IllegalStateException("Node not found: reference=$reference")
        }

        while (true) {
            when (result) {
                is PausableResult.WorkflowCompleted -> {
                    return result.output
                }

                is PausableResult.ActivityCompleted -> {
                    // Find the next node from the position reference
                    val nextNode = findNodeByReference(result.nextNodePosition)

                    // Continue execution from next node
                    result = PausableOrchestrator.run(nextNode, result.output, result.states.toMutableMap())
                }

                is PausableResult.WaitNeeded -> {
                    // delay(result.duration)

                    // Find the next node from the position reference
                    val nextNode = findNodeByReference(result.nextNodePosition)

                    // Continue execution from next node with the dataset from wait
                    result = PausableOrchestrator.run(nextNode, result.nextInput, result.states.toMutableMap())
                }

                is PausableResult.RetryNeeded -> {
                    // delay(result.duration)

                    // Find the next node from the position reference
                    val retryNode = findNodeByReference(result.nodePosition)

                    // Continue execution after retry delay - currentDataset unchanged
                    result = PausableOrchestrator.run(retryNode, result.input, result.states.toMutableMap())
                }

                is PausableResult.SubWorkflowNeeded -> {
                    // Execute child workflow recursively
                    val childNode = resolveChildWorkflow(result.childConfig)
                    val childOutput = if (result.childConfig.awaitCompletion) {
                        // await=true: Execute child and wait for output
                        runUntilComplete(childNode, result.childConfig.input, mutableMapOf())
                    } else {
                        // await=false (fire-and-forget): Use child's input as output
                        result.output!!
                    }

                    // Find the node that initiated the child workflow
                    val initiatingNode = findNodeByReference(result.nodePosition)

                    // Resume parent with child output
                    result = PausableOrchestrator.resumeFromChildWorkflow(
                        node = initiatingNode,
                        childOutput = childOutput,
                        states = result.states.toMutableMap()
                    )
                }
            }
        }
    }

    /**
     * Resolve a child workflow definition from the cache.
     */
    private fun resolveChildWorkflow(config: ChildWorkflowConfig): Node<*> {
        val namespace = WorkflowNamespace(config.namespace)
        val name = WorkflowName(config.name)
        val version = WorkflowVersion(config.version)

        val definition = DefinitionCache.getOrNull(namespace, name, version)
            ?: throw IllegalStateException(
                "Child workflow not found: namespace=$namespace, name=$name, version=$version"
            )

        return DefinitionCache.getRootNode(definition)
    }
}
