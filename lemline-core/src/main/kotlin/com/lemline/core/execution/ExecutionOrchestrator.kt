// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution

import com.lemline.core.execution.models.StepResult
import com.lemline.core.execution.nodes.NodeInstance
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.FlowDirectiveEnum
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Main execution loop coordinator for functional workflow execution.
 *
 * This object provides the core orchestration functions that implement the
 * functional workflow execution model:
 * - `enter`: First-time entry into a node from parent
 * - `reEnter`: Re-entry into a node from completed child
 * - `continueNavigation`: Navigation decision based on flow directive and state
 * - `exitToUp`: Compute output and return to parent
 * - `run`: Main dispatcher between enter and reEnter
 * - `execute`: Full workflow execution loop
 *
 * ## Execution Model
 *
 * The execution model is purely functional: each function takes the current state
 * and returns the next state as a tuple `(next, dataset, flowDirective)`.
 *
 * ```
 * while current is not null:
 *     (next, dataset, flowDirective) = run(current, dataset, flowDirective)
 *     current = next
 * ```
 *
 * ## State Safety
 *
 * The execution loop provides atomicity guarantees:
 * - State is cloned before each step
 * - On success: State is updated
 * - On exception: Workflow fails (error handling deferred to Phase 2)
 * - After successful run(): State is consistent for persistence (checkpoint)
 *
 * ## Dataset Flow
 *
 * The dataset flows functionally as parameters (never stored):
 * - Down: Parent output → child input
 * - Up: Child output → parent input
 * - Transformed at node boundaries (input.from, output.as)
 *
 * ## Simplified for Phase 1
 *
 * Error handling (try/catch/retry) is deferred to Phase 2.
 * Any exception during execution will fail the entire workflow.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val workflow = buildNodeInstance(definition)
 * val output = ExecutionOrchestrator.execute(workflow, input)
 * ```
 */
object ExecutionOrchestrator {

    /**
     * Execute workflow from input to completion.
     *
     * This is the main entry point for workflow execution. It runs the execution
     * loop until the workflow completes (current becomes null).
     *
     * Any exception during execution will propagate and fail the workflow.
     * Error handling (try/catch/retry) is deferred to Phase 2.
     *
     * @param workflow The root workflow instance
     * @param input The initial input dataset
     * @return The final output dataset
     * @throws Exception if any error occurs during execution
     */
    suspend fun execute(workflow: NodeInstance<*>, input: JsonElement): JsonElement {
        var current: NodeInstance<*>? = workflow
        var dataset = input
        var flowDirective: FlowDirective? = null

        while (current != null) {
            // Execute one step
            with(run(current, dataset, flowDirective)) {
                current = this.next
                dataset = this.dataset
                flowDirective = this.flowDirective
            }

            // ← Checkpoint: state is consistent for persistence
            // This is where the state could be serialized and saved
        }

        return dataset
    }

    /**
     * Execute a single step of workflow execution.
     *
     * Determines whether to enter node for first time (startedAt == null)
     * or re-enter after child completion (startedAt != null).
     *
     * @param current Current node to execute
     * @param dataset Dataset to process
     * @param flowDirective Navigation instruction (null on first entry)
     * @return StepResult with next node, dataset, and flow directive
     */
    suspend fun run(
        current: NodeInstance<*>,
        dataset: JsonElement,
        flowDirective: FlowDirective?
    ): StepResult {
        return if (current.state.startedAt == null) {
            // First time entering this node
            enter(current, dataset)
        } else {
            // Re-entering after child completed
            reEnter(current, dataset, flowDirective)
        }
    }

    /**
     * Enter node for the first time from parent.
     *
     * ## Phases:
     *
     * 1. **Conditional Check**: Evaluate `if` condition, skip if false
     * 2. **Initialize**: Set startedAt, validate, transform input, init state
     * 3. **Navigate**: Call continueNavigation() to decide where to go next
     *
     * @param node Node to enter
     * @param datasetFromParent Parent's output (becomes this node's input)
     * @return StepResult with next node, dataset, and flow directive
     */
    suspend fun enter(node: NodeInstance<*>, datasetFromParent: JsonElement): StepResult {
        // ===========================================
        // PHASE 1: Conditional Check
        // ===========================================

        // Check if condition - skip if false
        if (!node.checkIf(datasetFromParent)) {
            // Skip this node entirely (no state initialization)
            // Return to parent - parent will advance to next sibling
            return StepResult(
                next = node.parent,
                dataset = datasetFromParent,
                flowDirective = null  // Continue to next sibling
            )
        }

        // ===========================================
        // PHASE 2: Initialize Node State
        // ===========================================

        // Mark as started
        node.state.startedAt = Clock.System.now()

        // Set raw input for scope building
        node.rawInput = datasetFromParent

        // Validate input against schema (throws ValidationException)
        node.validateInput(datasetFromParent)

        // Apply input transformation (throws ExpressionException)
        val transformedInput = node.evaluateInput(datasetFromParent)

        // Initialize type-specific state (e.g., ForTask caches collection)
        node.state.init(transformedInput)

        // ===========================================
        // PHASE 3: Determine Next Step
        // ===========================================

        // Delegate to continueNavigation() to decide where to go
        return continueNavigation(node, transformedInput, null)
    }

    /**
     * Re-enter node after a child completes.
     *
     * Updates node's internal state based on child result, then delegates
     * to continueNavigation() to determine next step.
     *
     * @param node Node to re-enter
     * @param datasetFromChild Child's output result
     * @param flowDirective Navigation instruction from child's `.then` field
     * @return StepResult with next node, dataset, and flow directive
     */
    suspend fun reEnter(
        node: NodeInstance<*>,
        datasetFromChild: JsonElement,
        flowDirective: FlowDirective?
    ): StepResult {
        // ===========================================
        // Update Node State
        // ===========================================

        // Update internal state based on child's result
        // (node-type-specific: may store result, update indices, etc.)
        node.state.updateFromChild(datasetFromChild)

        // ===========================================
        // Determine Next Step
        // ===========================================

        // Delegate to continueNavigation() to decide where to go
        return continueNavigation(node, datasetFromChild, flowDirective)
    }

    /**
     * Determine next step based on FlowDirective and node state.
     *
     * ## Flow Directive Handling:
     *
     * - **null**: Default continue behavior (advance to next child or exit)
     * - **END**: Recursive unwinding to root (parent's parent will eventually be null)
     * - **EXIT**: Exit immediately to parent
     * - **CONTINUE**: Explicitly continue (same as null)
     * - **Goto(name)**: Jump to named sibling
     *
     * @param node Current node
     * @param datasetForNavigation Dataset for next step (transformedInput or child result)
     * @param flowDirective Navigation instruction (null means default continue)
     * @return StepResult with next node, dataset, and flow directive
     */
    suspend fun continueNavigation(
        node: NodeInstance<*>,
        datasetForNavigation: JsonElement,
        flowDirective: FlowDirective?
    ): StepResult {
        // Parse flow directive
        val directive = flowDirective?.get()

        return when (directive) {
            // END: Workflow complete - recursive unwinding
            is FlowDirectiveEnum -> when (directive) {
                FlowDirectiveEnum.END -> {
                    StepResult(
                        next = node.parent,
                        dataset = datasetForNavigation,
                        flowDirective = flowDirective  // Pass END up the chain
                    )
                }

                FlowDirectiveEnum.EXIT -> {
                    // Exit to parent immediately
                    exitToUp(node, datasetForNavigation)
                }

                FlowDirectiveEnum.CONTINUE -> {
                    // Explicit continue - same as null
                    handleContinue(node, datasetForNavigation)
                }
            }

            // Goto named sibling
            is String -> {
                handleGoto(node, datasetForNavigation, directive)
            }

            // Default continue (null or unrecognized)
            null -> {
                handleContinue(node, datasetForNavigation)
            }

            else -> {
                throw IllegalArgumentException("Unknown flow directive: $directive")
            }
        }
    }

    /**
     * Handle default continue behavior.
     *
     * Advances to next child or exits if done.
     */
    private suspend fun handleContinue(
        node: NodeInstance<*>,
        dataset: JsonElement
    ): StepResult {
        // Update state to advance to next child
        node.state.applyFlowDirective(null)  // null means default continue

        // Check if node has more work to do
        return if (node.state.shouldExit()) {
            exitToUp(node, dataset)
        } else {
            val nextChildIndex = node.state.nextChildIndex()
            val nextChild = node.children[nextChildIndex]
            StepResult(
                next = nextChild,
                dataset = dataset,
                flowDirective = null
            )
        }
    }

    /**
     * Handle goto named sibling.
     */
    private suspend fun handleGoto(
        node: NodeInstance<*>,
        dataset: JsonElement,
        targetName: String
    ): StepResult {
        // Apply goto directive to state
        node.state.applyFlowDirective(targetName)

        // Get target child
        val nextChildIndex = node.state.nextChildIndex()
        val nextChild = node.children[nextChildIndex]

        return StepResult(
            next = nextChild,
            dataset = dataset,
            flowDirective = null
        )
    }

    /**
     * Compute output and return to parent.
     *
     * ## Flow:
     *
     * 1. Execute action (for activity tasks)
     * 2. Apply output transformation
     * 3. Validate output schema
     * 4. Clear this node's state
     * 5. Return to parent with transformed output
     *
     * @param node Node to exit
     * @param datasetForExit Dataset to use for computing output
     * @return StepResult with parent node, transformed output, and flow directive
     */
    private suspend fun exitToUp(node: NodeInstance<*>, datasetForExit: JsonElement): StepResult {
        // ===========================================
        // Compute Output
        // ===========================================

        // Execute action (e.g., HTTP call, set data)
        // For flow tasks, this just returns input unchanged
        val rawOutput = node.execute(datasetForExit)

        // Set raw output for scope building
        node.rawOutput = rawOutput

        // Apply output transformation (throws ExpressionException)
        val transformedOutput = node.evaluateOutput(rawOutput)

        // Validate output schema (throws ValidationException)
        node.validateOutput(transformedOutput)

        // ===========================================
        // Prepare Return
        // ===========================================

        // Clear this node's state
        node.state.clear()

        // Get flow directive from definition
        val flowDirective = node.getFlowDirective()

        // Return to parent
        return StepResult(
            next = node.parent,
            dataset = transformedOutput,
            flowDirective = flowDirective
        )
    }
}
