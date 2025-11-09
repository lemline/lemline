# Workflow Execution Implementation Specification

**Version**: 1.2
**Date Started**: 2025-01-08
**Date Updated**: 2025-11-09
**Status**: In Progress - Phase 1A ✅ | Phase 1B ✅ | Phase 1C ✅

> **Implementation Status**:
> - Phase 1A: Core foundation complete and compiling (~1,437 lines)
> - Phase 1B: DoTask and SetTask implementations complete with tests (~381 lines)
> - Phase 1C: ForTask iteration complete with scope variables (~218 lines + 3 tests)
>
> See [PHASE_1_COMPLETE.md](./PHASE_1_COMPLETE.md) for details.

This document provides a detailed technical specification for implementing the functional workflow execution model described in [workflow-execution-formal-model.md](workflow-execution-formal-model.md).

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Core Components Design](#core-components-design)
3. [Data Models](#data-models)
4. [API Specifications](#api-specifications)
5. [State Management](#state-management)
6. [Expression Evaluation](#expression-evaluation)
7. [Error Handling](#error-handling)
8. [Serialization Strategy](#serialization-strategy)
9. [Migration Strategy](#migration-strategy)
10. [Testing Strategy](#testing-strategy)
11. [Performance Considerations](#performance-considerations)
12. [Implementation Phases](#implementation-phases)

---

## Architecture Overview

### Current vs New Architecture

**Current Architecture** (`Processor.kt`):
- Mixed orchestration and execution logic
- Exception-driven control flow (throws for waits, retries, completion)
- NodeInstance classes contain both state and behavior
- Position-based navigation with going up/down/side

**New Architecture** (Functional Model):
- Separation: free functions (orchestration) vs instance methods (scope-dependent operations)
- Return-based control flow: `(next, dataset, flowDirective)` tuples
- Immutable/mutable state separation for efficient serialization
- Functional tree traversal with explicit state transitions

### Component Layers

```
┌─────────────────────────────────────────────────────────────┐
│                     StepByStepRunner                         │
│  (Infrastructure: messaging, persistence, scheduling)        │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   Execution Orchestrator                     │
│  (Free functions: enter, reEnter, continue, exitToUp, run)  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      Node Instances                          │
│  (Instance methods: checkIf, evaluateInput, execute, etc.)  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    State Management                          │
│  (NodeState<M>: immutable fields + mutable data classes)    │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  Expression Evaluator                        │
│            (Scope building, JQ evaluation)                   │
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components Design

### 1. Execution Orchestrator

**Package**: `com.lemline.core.execution`

**File**: `ExecutionOrchestrator.kt`

```kotlin
package com.lemline.core.execution

import com.lemline.core.models.*
import kotlinx.serialization.json.JsonElement

/**
 * Result of a single execution step.
 *
 * @property next The next node to execute (null if workflow complete)
 * @property dataset The dataset to pass to the next node
 * @property flowDirective The navigation instruction
 */
data class StepResult(
    val next: Node?,
    val dataset: JsonElement,
    val flowDirective: FlowDirective
)

/**
 * Main execution loop coordinator.
 */
object ExecutionOrchestrator {

    /**
     * Execute workflow from input to completion.
     *
     * @param workflow The workflow definition
     * @param input The initial input dataset
     * @return The final output dataset
     * @throws WorkflowFailedException if unhandled exception occurs
     */
    fun execute(workflow: Workflow, input: JsonElement): JsonElement {
        var current: Node? = workflow.rootNode
        var dataset = input
        var flowDirective: FlowDirective = FlowDirective.Continue

        while (current != null) {
            // Save state for rollback
            val currentState = current.state.clone()

            try {
                // Execute one step
                val result = run(current, dataset, flowDirective)

                // ← Checkpoint: state is consistent for persistence

                // Move to next node
                current = result.next
                dataset = result.dataset
                flowDirective = result.flowDirective

            } catch (e: WorkflowException) {
                // Rollback state
                current.state = currentState

                // Handle exception
                val recovery = handleException(current, dataset, e)
                current = recovery.next
                dataset = recovery.dataset
                flowDirective = recovery.flowDirective
            }
        }

        return dataset
    }

    /**
     * Execute a single step of workflow execution.
     * Determines whether to enter node for first time or re-enter after child.
     */
    fun run(
        current: Node,
        dataset: JsonElement,
        flowDirective: FlowDirective
    ): StepResult {
        return if (current.state.startedAt == null) {
            enter(current, dataset)
        } else {
            reEnter(current, dataset, flowDirective)
        }
    }

    /**
     * Enter node for the first time from parent.
     */
    fun enter(node: Node, datasetFromParent: JsonElement): StepResult {
        // Phase 1: Conditional check
        if (!node.checkIf(datasetFromParent)) {
            // Skip this node
            return StepResult(
                next = node.parent,
                dataset = datasetFromParent,
                flowDirective = FlowDirective.Continue
            )
        }

        // Phase 2: Initialize state
        node.state.startedAt = Clock.System.now()

        node.validateInput(datasetFromParent)
        val transformedInput = node.evaluateInput(datasetFromParent)

        node.state.init(transformedInput)

        // Phase 3: Determine next step
        return continue(node, transformedInput, FlowDirective.Continue)
    }

    /**
     * Re-enter node after a child completes.
     */
    fun reEnter(
        node: Node,
        datasetFromChild: JsonElement,
        flowDirective: FlowDirective
    ): StepResult {
        // Update state based on child result
        node.state.updateFromChild(datasetFromChild)

        // Determine next step
        return continue(node, datasetFromChild, flowDirective)
    }

    /**
     * Determine next step based on FlowDirective and node state.
     */
    fun continue(
        node: Node,
        dataset: JsonElement,
        flowDirective: FlowDirective
    ): StepResult {
        return when (flowDirective) {
            is FlowDirective.End -> {
                // Recursive unwinding to root
                StepResult(
                    next = node.parent,
                    dataset = dataset,
                    flowDirective = FlowDirective.End
                )
            }

            is FlowDirective.Exit -> {
                // Exit to parent immediately
                exitToUp(node, dataset)
            }

            is FlowDirective.Continue,
            is FlowDirective.Goto -> {
                // Update state: advance or jump
                node.state.applyFlowDirective(flowDirective)

                // Check if done
                if (node.state.shouldExit()) {
                    exitToUp(node, dataset)
                } else {
                    val nextChild = node.children[node.state.nextChildIndex()]
                    StepResult(
                        next = nextChild,
                        dataset = dataset,
                        flowDirective = FlowDirective.Continue
                    )
                }
            }
        }
    }

    /**
     * Compute output and return to parent.
     */
    fun exitToUp(node: Node, datasetForExit: JsonElement): StepResult {
        // Execute action (if any)
        val rawOutput = node.execute(datasetForExit)

        // Transform output
        val transformedOutput = node.evaluateOutput(rawOutput)

        // Validate output
        node.validateOutput(transformedOutput)

        // Clear state
        clearState(node)

        // Return to parent
        return StepResult(
            next = node.parent,
            dataset = transformedOutput,
            flowDirective = node.definition.then ?: FlowDirective.Continue
        )
    }

    /**
     * Clear node state for cleanup.
     */
    private fun clearState(node: Node) {
        node.state.clear()
        // State will be removed from InstanceState.currentStates map
    }
}
```

### 2. Error Handler

**File**: `ErrorHandler.kt`

```kotlin
package com.lemline.core.execution

/**
 * Error handling orchestration.
 */
object ErrorHandler {

    /**
     * Handle exception during workflow execution.
     *
     * @return Recovery step result (retry, catch, or fail)
     */
    fun handleException(
        current: Node,
        dataset: JsonElement,
        exception: WorkflowException
    ): StepResult {
        val tryTask = findHandlingTry(current, exception)

        if (tryTask != null) {
            val tryState = tryTask.state as TryTaskState

            return if (tryState.shouldRetry()) {
                retryTryBlock(tryTask, current)
            } else {
                enterCatch(tryTask, current, exception)
            }
        } else {
            throw WorkflowFailedException("Unhandled exception: ${exception.message}", exception)
        }
    }

    /**
     * Find nearest TryTask that can handle this error.
     */
    private fun findHandlingTry(node: Node?, exception: WorkflowException): Node? {
        if (node == null) return null

        if (node is TryTaskNode) {
            val state = node.state as TryTaskState
            if (state.shouldRetry() || node.canCatch(exception)) {
                return node
            }
        }

        return findHandlingTry(node.parent, exception)
    }

    /**
     * Retry the try block with original input.
     */
    private fun retryTryBlock(tryTask: Node, failingNode: Node): StepResult {
        val tryState = tryTask.state as TryTaskState

        // Reset state from failing node up to try body
        resetStateUpTo(failingNode, tryTask.children[0])

        // Increment attempt counter
        tryState.incrementAttempt()

        // Get try body
        val tryBody = tryTask.children[tryState.nextChildIndex()]

        // Return to try body with original input
        return StepResult(
            next = tryBody,
            dataset = tryState.transformedInput!!,
            flowDirective = FlowDirective.Continue
        )
    }

    /**
     * Enter appropriate catch block.
     */
    private fun enterCatch(
        tryTask: Node,
        failingNode: Node,
        exception: WorkflowException
    ): StepResult {
        val tryState = tryTask.state as TryTaskState

        // Reset state from failing node up to try body
        resetStateUpTo(failingNode, tryTask.children[0])

        // Prepare dataset with error info
        val datasetWithError = mergeError(tryState.transformedInput!!, exception)

        // Update state to enter catch mode
        val catchIndex = tryState.findMatchingCatch(exception, tryTask.definition.catch!!)
        tryState.enterCatch(exception, catchIndex)

        // Get catch block
        val catchBlock = tryTask.children[tryState.nextChildIndex()]

        return StepResult(
            next = catchBlock,
            dataset = datasetWithError,
            flowDirective = FlowDirective.Continue
        )
    }

    /**
     * Reset state from start (inclusive) up to end (exclusive).
     */
    private fun resetStateUpTo(start: Node, end: Node) {
        var current: Node? = start
        while (current != null && current != end) {
            current.state.clear()
            current = current.parent
        }
    }

    /**
     * Merge error information into dataset.
     */
    private fun mergeError(dataset: JsonElement, exception: WorkflowException): JsonElement {
        // Implementation: merge error object into dataset
        // Returns: { ...dataset, error: { type, status, title, details } }
        TODO("Implement error merging")
    }
}
```

### 3. Node Base Class

**File**: `Node.kt`

```kotlin
package com.lemline.core.models

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Base class for all workflow nodes.
 * Nodes represent workflow tasks in the tree structure.
 */
abstract class Node(
    val definition: TaskDefinition,
    val parent: Node? = null
) {
    /**
     * Child nodes (horizontal siblings).
     */
    abstract val children: List<Node>

    /**
     * Current execution state.
     * Contains both immutable fields (cached) and mutable fields (serialized).
     */
    abstract var state: NodeState<*>

    /**
     * Unique position in tree (for serialization).
     */
    val position: NodePosition by lazy {
        computePosition()
    }

    // ========================================
    // Instance Methods (Scope-Dependent)
    // ========================================

    /**
     * Check if condition for conditional execution.
     * Uses scope for expression evaluation.
     *
     * @return true if task should execute, false to skip
     */
    fun checkIf(dataset: JsonElement): Boolean {
        val ifCondition = definition.`if` ?: return true

        val scope = buildScope(dataset, null)
        return ExpressionEvaluator.evaluateBoolean(ifCondition, dataset, scope)
    }

    /**
     * Validate input against schema.
     * Uses scope for error context.
     */
    fun validateInput(dataset: JsonElement) {
        val schema = definition.input?.schema ?: return

        val scope = buildScope(dataset, null)
        SchemaValidator.validate(dataset, schema, scope)
    }

    /**
     * Transform input using input.from expression.
     * Uses scope for expression evaluation.
     */
    fun evaluateInput(dataset: JsonElement): JsonElement {
        val inputFrom = definition.input?.from ?: return dataset

        val scope = buildScope(dataset, null)
        return ExpressionEvaluator.evaluate(inputFrom, dataset, scope)
    }

    /**
     * Execute node action (for activity tasks).
     * May use scope for expression evaluation in action parameters.
     */
    open fun execute(input: JsonElement): JsonElement {
        // Default: no action, pass through
        return input
    }

    /**
     * Transform output using output.as expression.
     * Uses scope for expression evaluation.
     */
    fun evaluateOutput(output: JsonElement): JsonElement {
        val outputAs = definition.output?.`as` ?: return output

        val scope = buildScope(null, output)
        return ExpressionEvaluator.evaluate(outputAs, output, scope)
    }

    /**
     * Validate output against schema.
     * Uses scope for error context.
     */
    fun validateOutput(output: JsonElement) {
        val schema = definition.output?.schema ?: return

        val scope = buildScope(null, output)
        SchemaValidator.validate(output, schema, scope)
    }

    /**
     * Build hierarchical scope for expression evaluation.
     *
     * @param input Raw input (for $input variable)
     * @param output Raw output (for $output variable)
     * @return Complete scope with task context, parent scopes, and node variables
     */
    protected open fun buildScope(
        input: JsonElement?,
        output: JsonElement?
    ): Scope {
        // Start with node-specific variables
        val nodeVariables = getNodeVariables()

        // Build task descriptor
        val taskDescriptor = TaskDescriptor(
            name = definition.name,
            reference = position.toString(),
            definition = definition,
            input = input,
            output = output,
            startedAt = state.startedAt
        )

        // Create current scope
        val currentScope = Scope(
            context = getWorkflowContext(),
            input = input,
            output = output,
            task = taskDescriptor,
            workflow = getWorkflowDescriptor(),
            runtime = getRuntimeDescriptor(),
            secrets = getSecrets(),
            authorization = getAuthorizationDescriptor(),
            variables = nodeVariables
        )

        // Merge with parent scope
        return if (parent != null) {
            currentScope.mergeWith(parent.buildScope(null, null))
        } else {
            currentScope
        }
    }

    /**
     * Get node-specific variables for scope.
     * Override in subclasses (e.g., ForTask returns $item, $index).
     */
    protected open fun getNodeVariables(): Map<String, JsonElement> {
        return emptyMap()
    }

    // Helper methods for scope building
    protected abstract fun getWorkflowContext(): JsonElement
    protected abstract fun getWorkflowDescriptor(): WorkflowDescriptor
    protected abstract fun getRuntimeDescriptor(): RuntimeDescriptor
    protected abstract fun getSecrets(): Map<String, Any>
    protected abstract fun getAuthorizationDescriptor(): AuthorizationDescriptor

    private fun computePosition(): NodePosition {
        // Implementation: compute position from parent chain
        TODO("Implement position computation")
    }
}
```

---

## Data Models

### 1. FlowDirective

**File**: `FlowDirective.kt`

```kotlin
package com.lemline.core.models

/**
 * Navigation instruction that guides execution flow.
 */
sealed class FlowDirective {
    /**
     * Continue to next sibling in parent's child list.
     */
    data object Continue : FlowDirective()

    /**
     * Return to parent immediately.
     */
    data object Exit : FlowDirective()

    /**
     * Return to root (complete workflow).
     */
    data object End : FlowDirective()

    /**
     * Jump to specific sibling task by name.
     */
    data class Goto(val taskName: String) : FlowDirective()

    companion object {
        /**
         * Parse flow directive from task definition.
         */
        fun from(then: String?): FlowDirective {
            return when (then?.lowercase()) {
                null, "continue" -> Continue
                "exit" -> Exit
                "end" -> End
                else -> Goto(then)
            }
        }
    }
}
```

### 2. NodeState Base Class

**File**: `NodeState.kt`

```kotlin
package com.lemline.core.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Base class for node execution state.
 * Separates immutable fields (cached, not serialized) from mutable fields (serialized).
 *
 * @param M The mutable state type (must be a data class for efficient serialization)
 */
abstract class NodeState<M>(
    /**
     * When the node started execution (immutable, cached).
     */
    open var startedAt: Instant? = null,

    /**
     * Mutable state that gets serialized for resumption.
     */
    open var mutable: M
) {
    /**
     * Check if node has completed its work.
     */
    abstract fun shouldExit(): Boolean

    /**
     * Get index of next child to process.
     */
    abstract fun nextChildIndex(): Int

    /**
     * Initialize state with transformed input (called once at enter).
     */
    open fun init(transformedInput: JsonElement) {
        // Default: no initialization needed
        // Override in subclasses that need to cache input (e.g., TryTask, ForTask)
    }

    /**
     * Update state when re-entering from child (called at reEnter).
     */
    open fun updateFromChild(datasetFromChild: JsonElement) {
        // Default: no update needed
        // Override in subclasses that accumulate results
    }

    /**
     * Apply flow directive to update state (called at continue).
     */
    abstract fun applyFlowDirective(flowDirective: FlowDirective)

    /**
     * Clear state for cleanup.
     */
    open fun clear() {
        startedAt = null
        // Subclasses should reset mutable state
    }

    /**
     * Clone state for rollback.
     */
    abstract fun clone(): NodeState<M>
}
```

### 3. DoTask State

**File**: `DoTaskState.kt`

```kotlin
package com.lemline.core.models.state

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mutable state for DoTask (serialized).
 */
@Serializable
data class DoMutableState(
    val childIndex: Int = -1
)

/**
 * Complete state for DoTask.
 * Executes children sequentially in order.
 */
class DoTaskState(
    /**
     * Number of children (immutable, cached from definition).
     */
    val doSize: Int,

    override var startedAt: Instant? = null,
    override var mutable: DoMutableState = DoMutableState()
) : NodeState<DoMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        return mutable.childIndex >= doSize
    }

    override fun nextChildIndex(): Int {
        return mutable.childIndex
    }

    override fun applyFlowDirective(flowDirective: FlowDirective) {
        when (flowDirective) {
            is FlowDirective.Continue -> {
                // Advance to next child
                mutable = mutable.copy(childIndex = mutable.childIndex + 1)
            }
            is FlowDirective.Goto -> {
                // Jump to named child
                val targetIndex = findChildIndexByName(flowDirective.taskName)
                mutable = mutable.copy(childIndex = targetIndex)
            }
            else -> {
                // Exit, End handled by continue()
            }
        }
    }

    override fun clone(): NodeState<DoMutableState> {
        return DoTaskState(
            doSize = doSize,
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }

    private fun findChildIndexByName(name: String): Int {
        // Implementation: search children by name
        TODO("Implement child name lookup")
    }
}
```

### 4. ForTask State

**File**: `ForTaskState.kt`

```kotlin
package com.lemline.core.models.state

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mutable state for ForTask (serialized).
 */
@Serializable
data class ForMutableState(
    val forIndex: Int = -1
)

/**
 * Complete state for ForTask.
 * Executes child repeatedly for each item in collection.
 *
 * ## Implementation Note: Lazy Collection Evaluation
 *
 * The collection is evaluated lazily in ForNodeInstance.scope getter to avoid
 * circular dependencies:
 * - scope getter needs to add $item and $index variables
 * - To get current item, we need the evaluated collection
 * - Collection evaluation (JQ expression) requires scope
 * - This creates a circular dependency
 *
 * Solution:
 * 1. Initialize ForTaskState with empty collection
 * 2. In scope getter, check if collection is empty and rawInput is available
 * 3. Evaluate collection expression using parent scope (not current scope)
 * 4. Use isInitializing flag to prevent re-entry during evaluation
 * 5. Update state with evaluated collection
 *
 * This approach ensures collection is evaluated once with proper scope context.
 */
class ForTaskState(
    /**
     * Collection to iterate over (immutable, cached from expression evaluation).
     * Evaluated lazily on first access to avoid circular dependency.
     */
    val collection: List<JsonElement>,

    /**
     * While condition expression (immutable, from definition).
     */
    val whileCondition: String? = null,

    /**
     * Iteration variable names (immutable, from definition).
     */
    val itemVarName: String = "item",
    val indexVarName: String = "index",

    override var startedAt: Instant? = null,
    override var mutable: ForMutableState = ForMutableState()
) : NodeState<ForMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        // Check if past end of collection
        if (mutable.forIndex >= collection.size) return true

        // Check while condition if present
        if (whileCondition != null && !evaluateWhile()) {
            return true
        }

        return false
    }

    override fun nextChildIndex(): Int {
        // Always return 0 (the do body) - same child, different iteration
        return 0
    }

    override fun applyFlowDirective(flowDirective: FlowDirective) {
        when (flowDirective) {
            is FlowDirective.Continue -> {
                // Advance to next iteration
                mutable = mutable.copy(forIndex = mutable.forIndex + 1)
            }
            else -> {
                // Exit, End handled by continue()
            }
        }
    }

    override fun init(transformedInput: JsonElement) {
        // Collection is evaluated lazily in ForNodeInstance.scope getter
        // This avoids circular dependency between scope and collection evaluation
        // Nothing to do here
    }

    override fun clone(): NodeState<ForMutableState> {
        return ForTaskState(
            collection = collection,
            whileCondition = whileCondition,
            itemVarName = itemVarName,
            indexVarName = indexVarName,
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }

    /**
     * Get current item for scope.
     */
    fun getCurrentItem(): JsonElement {
        return collection[mutable.forIndex]
    }

    /**
     * Get current index for scope.
     */
    fun getCurrentIndex(): Int {
        return mutable.forIndex
    }

    /**
     * Evaluate while condition against current scope.
     */
    private fun evaluateWhile(): Boolean {
        // Implementation: evaluate while condition with current item/index in scope
        TODO("Implement while condition evaluation")
    }
}
```

### 5. TryTask State

**File**: `TryTaskState.kt`

```kotlin
package com.lemline.core.models.state

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mutable state for TryTask (serialized).
 */
@Serializable
data class TryMutableState(
    val attemptIndex: Int = 0,
    val inCatch: Boolean = false,
    val catchIndex: Int = -1,
    val lastError: WorkflowError? = null
)

/**
 * Complete state for TryTask.
 * Attempts execution with catch blocks for errors.
 */
class TryTaskState(
    /**
     * Maximum retry attempts (immutable, from definition).
     */
    val maxAttempts: Int,

    /**
     * Cached transformed input for retries (immutable, NOT serialized).
     * Recomputed on resume from parent's output.
     */
    var transformedInput: JsonElement? = null,

    override var startedAt: Instant? = null,
    override var mutable: TryMutableState = TryMutableState()
) : NodeState<TryMutableState>(startedAt, mutable) {

    override fun shouldExit(): Boolean {
        // Done if in catch block (catch completed)
        if (mutable.inCatch) return true

        // Done if try succeeded or retries exhausted
        if (mutable.attemptIndex >= maxAttempts) return true

        return false
    }

    override fun nextChildIndex(): Int {
        return if (mutable.inCatch) {
            // Return catch block index (1-based, 0 is try body)
            1 + mutable.catchIndex
        } else {
            0  // Try body
        }
    }

    override fun applyFlowDirective(flowDirective: FlowDirective) {
        when (flowDirective) {
            is FlowDirective.Continue -> {
                if (mutable.inCatch) {
                    // Catch block completed - no state change needed
                } else {
                    // Try block completed successfully - skip retries
                    mutable = mutable.copy(attemptIndex = maxAttempts)
                }
            }
            else -> {
                // Exit, End handled by continue()
            }
        }
    }

    override fun init(transformedInput: JsonElement) {
        // Cache transformed input for retries
        this.transformedInput = transformedInput
    }

    override fun clone(): NodeState<TryMutableState> {
        return TryTaskState(
            maxAttempts = maxAttempts,
            transformedInput = transformedInput,
            startedAt = startedAt,
            mutable = mutable.copy()
        )
    }

    /**
     * Check if should retry (called by error handler).
     */
    fun shouldRetry(): Boolean {
        return mutable.attemptIndex < maxAttempts && !mutable.inCatch
    }

    /**
     * Increment retry attempt (called by error handler).
     */
    fun incrementAttempt() {
        mutable = mutable.copy(attemptIndex = mutable.attemptIndex + 1)
    }

    /**
     * Transition to catch block (called by error handler).
     */
    fun enterCatch(exception: WorkflowError, catchIndex: Int) {
        mutable = TryMutableState(
            attemptIndex = mutable.attemptIndex,
            inCatch = true,
            catchIndex = catchIndex,
            lastError = exception
        )

        // Clear cached input to save memory
        transformedInput = null
    }

    /**
     * Find which catch block matches this exception.
     */
    fun findMatchingCatch(exception: WorkflowError, catchBlocks: List<CatchDef>): Int {
        for ((index, catchDef) in catchBlocks.withIndex()) {
            // Catch all if no error filter
            if (catchDef.errors == null) return index

            // Check error type match
            if (catchDef.errors.contains(exception.type)) return index
        }

        throw IllegalStateException("No matching catch block found")
    }
}
```

### 6. Scope

**File**: `Scope.kt`

```kotlin
package com.lemline.core.evaluation

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Task descriptor for scope ($task.*).
 */
data class TaskDescriptor(
    val name: String,
    val reference: String,
    val definition: TaskDefinition,
    val input: JsonElement?,
    val output: JsonElement?,
    val startedAt: Instant?
)

/**
 * Workflow descriptor for scope ($workflow.*).
 */
data class WorkflowDescriptor(
    val id: String,
    val name: String,
    val namespace: String,
    val version: String
)

/**
 * Runtime descriptor for scope ($runtime.*).
 */
data class RuntimeDescriptor(
    val now: Instant,
    val executionId: String
)

/**
 * Authorization descriptor for scope ($authorization.*).
 */
data class AuthorizationDescriptor(
    val subject: String?,
    val claims: Map<String, Any>
)

/**
 * Hierarchical scope for expression evaluation.
 */
data class Scope(
    val context: JsonElement,
    val input: JsonElement?,
    val output: JsonElement?,
    val task: TaskDescriptor,
    val workflow: WorkflowDescriptor,
    val runtime: RuntimeDescriptor,
    val secrets: Map<String, Any>,
    val authorization: AuthorizationDescriptor,
    val variables: Map<String, JsonElement>
) {
    /**
     * Merge with parent scope.
     * Current scope values take precedence.
     */
    fun mergeWith(parent: Scope): Scope {
        return Scope(
            context = this.context,  // Context is workflow-level, same for all
            input = this.input ?: parent.input,
            output = this.output ?: parent.output,
            task = this.task,  // Each task has its own descriptor
            workflow = this.workflow,  // Workflow-level, same for all
            runtime = this.runtime,  // Runtime-level, same for all
            secrets = this.secrets,  // Secrets are workflow-level
            authorization = this.authorization,  // Auth is workflow-level
            variables = this.variables + parent.variables  // Merge variables, child wins
        )
    }

    /**
     * Convert scope to JSON for expression evaluation.
     */
    fun toJson(): JsonElement {
        // Implementation: convert all scope fields to JSON
        TODO("Implement scope to JSON conversion")
    }
}
```

---

## API Specifications

### 1. InstanceState (Serializable Workflow State)

**File**: `InstanceState.kt`

```kotlin
package com.lemline.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Complete workflow instance state.
 * This is what gets serialized and sent in InstanceMessage.
 */
@Serializable
data class InstanceState(
    /**
     * Unique workflow instance ID (UUID v7).
     */
    val id: String,

    /**
     * Workflow definition reference.
     */
    val namespace: String,
    val name: String,
    val version: String,

    /**
     * Current node position in tree.
     */
    val currentPosition: NodePosition,

    /**
     * Map of node states (only nodes with non-empty mutable state).
     * Key: NodePosition
     * Value: Serialized mutable state (type determined by node type)
     */
    val nodeStates: Map<NodePosition, SerializedMutableState>,

    /**
     * Workflow-level context (exported values).
     */
    val context: JsonElement,

    /**
     * Workflow status.
     */
    val status: WorkflowStatus,

    /**
     * Metadata.
     */
    val startedAt: kotlinx.datetime.Instant,
    val updatedAt: kotlinx.datetime.Instant
)

/**
 * Workflow execution status.
 */
enum class WorkflowStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAULTED,
    WAITING
}

/**
 * Node position in tree.
 * Examples: [0], [0, "taskName", "do", 1]
 */
@Serializable
data class NodePosition(
    val tokens: List<String>
) {
    override fun toString(): String {
        return tokens.joinToString("/")
    }

    companion object {
        fun parse(s: String): NodePosition {
            return NodePosition(s.split("/"))
        }
    }
}

/**
 * Serialized mutable state wrapper.
 * Uses polymorphic serialization to handle different state types.
 */
@Serializable
sealed class SerializedMutableState {
    @Serializable
    data class Do(val state: DoMutableState) : SerializedMutableState()

    @Serializable
    data class For(val state: ForMutableState) : SerializedMutableState()

    @Serializable
    data class Try(val state: TryMutableState) : SerializedMutableState()

    @Serializable
    data class Switch(val state: SwitchMutableState) : SerializedMutableState()

    @Serializable
    data class Activity(val state: ActivityMutableState) : SerializedMutableState()
}
```

### 2. State Hydration

**File**: `StateHydrator.kt`

```kotlin
package com.lemline.core.execution

/**
 * Hydrates workflow tree from serialized state.
 */
object StateHydrator {

    /**
     * Restore workflow tree from instance state.
     *
     * @param definition Workflow definition
     * @param instanceState Serialized instance state
     * @return Hydrated workflow tree with current node
     */
    fun hydrate(
        definition: WorkflowDefinition,
        instanceState: InstanceState
    ): Pair<Node, JsonElement> {
        // Build workflow tree from definition
        val rootNode = buildTree(definition)

        // Restore node states from serialized state
        restoreStates(rootNode, instanceState.nodeStates)

        // Find current node by position
        val currentNode = findNodeByPosition(rootNode, instanceState.currentPosition)

        // Dataset must be recomputed by re-executing from parent
        // For now, return empty dataset (will be computed by runner)
        val dataset = computeDatasetForNode(currentNode)

        return Pair(currentNode, dataset)
    }

    /**
     * Build node tree from workflow definition.
     */
    private fun buildTree(definition: WorkflowDefinition): Node {
        // Implementation: recursively build tree
        TODO("Implement tree building from definition")
    }

    /**
     * Restore node states from serialized map.
     */
    private fun restoreStates(
        root: Node,
        nodeStates: Map<NodePosition, SerializedMutableState>
    ) {
        // Implementation: walk tree and restore states
        TODO("Implement state restoration")
    }

    /**
     * Find node by position in tree.
     */
    private fun findNodeByPosition(root: Node, position: NodePosition): Node {
        // Implementation: navigate tree by position tokens
        TODO("Implement node lookup by position")
    }

    /**
     * Compute dataset for resuming at node.
     * This requires re-executing parent to get output.
     */
    private fun computeDatasetForNode(node: Node): JsonElement {
        // Implementation: re-execute parent if needed
        TODO("Implement dataset computation for resume")
    }
}
```

---

## State Management

### Serialization Strategy

**What Gets Serialized**:
1. `InstanceState.id` - Workflow instance ID
2. `InstanceState.currentPosition` - Current node position
3. `InstanceState.nodeStates` - Map of `NodePosition` → `SerializedMutableState`
4. `InstanceState.context` - Workflow context
5. `InstanceState.status` - Workflow status

**What Does NOT Get Serialized**:
1. Dataset (recomputed from parent)
2. Immutable node fields (`startedAt`, `collection`, `doSize`, etc.)
3. Node tree structure (rebuilt from definition)
4. Scope (computed from state)

**Serialization Format**:

```json
{
  "id": "01930b3c-4f85-7000-8000-000000000000",
  "namespace": "orders",
  "name": "process-order",
  "version": "1.0.0",
  "currentPosition": "0/validateOrder/do/1/callAPI",
  "nodeStates": {
    "0": {
      "type": "do",
      "childIndex": 0
    },
    "0/validateOrder": {
      "type": "try",
      "attemptIndex": 1,
      "inCatch": false,
      "catchIndex": -1,
      "lastError": null
    },
    "0/validateOrder/do": {
      "type": "do",
      "childIndex": 1
    }
  },
  "context": {
    "customerId": "12345",
    "orderTotal": 150.00
  },
  "status": "RUNNING",
  "startedAt": "2025-01-08T10:00:00Z",
  "updatedAt": "2025-01-08T10:00:05Z"
}
```

### State Compression Benefits

**Current Implementation** (estimated per-node overhead):
- `NodePosition`: ~50 bytes
- `startedAt`: 8 bytes (Instant)
- `collection` (ForTask): ~100-1000 bytes (depends on size)
- `transformedInput` (TryTask): ~100-1000 bytes
- Total: ~150-1000+ bytes per node

**New Implementation** (estimated per-node overhead):
- `NodePosition`: ~50 bytes (key in map)
- `MutableState`: 4-16 bytes (just indices and flags)
- Total: ~54-66 bytes per node

**Compression Ratio**: ~60-95% reduction in serialized state size

---

## Expression Evaluation

### ExpressionEvaluator Integration

**File**: `ExpressionEvaluator.kt` (updated)

```kotlin
package com.lemline.core.evaluation

/**
 * JQ expression evaluator with scope support.
 */
object ExpressionEvaluator {

    /**
     * Evaluate expression against dataset with scope.
     *
     * @param expression JQ expression string
     * @param dataset Input dataset (. references this)
     * @param scope Hierarchical scope ($ references this)
     * @return Evaluated result
     */
    fun evaluate(
        expression: String,
        dataset: JsonElement,
        scope: Scope
    ): JsonElement {
        // Merge scope into evaluation context
        val context = buildContext(dataset, scope)

        // Evaluate JQ expression
        return jq.evaluate(expression, context)
    }

    /**
     * Evaluate boolean expression.
     */
    fun evaluateBoolean(
        expression: String,
        dataset: JsonElement,
        scope: Scope
    ): Boolean {
        val result = evaluate(expression, dataset, scope)
        return result.toBoolean()
    }

    /**
     * Build evaluation context by merging dataset and scope.
     */
    private fun buildContext(dataset: JsonElement, scope: Scope): JsonElement {
        // Implementation: create context object
        // {
        //   ".": dataset,
        //   "$context": scope.context,
        //   "$input": scope.input,
        //   "$output": scope.output,
        //   "$task": scope.task.toJson(),
        //   "$workflow": scope.workflow.toJson(),
        //   ... etc
        // }
        TODO("Implement context building")
    }
}
```

---

## Error Handling

### Exception Types

**File**: `Exceptions.kt`

```kotlin
package com.lemline.core.exceptions

/**
 * Base class for all workflow exceptions.
 */
sealed class WorkflowException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    abstract val type: String
}

/**
 * Validation error (schema validation, input/output validation).
 */
class ValidationException(
    message: String,
    val errors: List<String>,
    cause: Throwable? = null
) : WorkflowException(message, cause) {
    override val type = "validation"
}

/**
 * Expression evaluation error (JQ syntax error, runtime error).
 */
class ExpressionException(
    message: String,
    val expression: String,
    cause: Throwable? = null
) : WorkflowException(message, cause) {
    override val type = "expression"
}

/**
 * Action execution error (HTTP error, script error, etc.).
 */
class ActionException(
    message: String,
    val actionType: String,
    val status: Int? = null,
    cause: Throwable? = null
) : WorkflowException(message, cause) {
    override val type = "action"
}

/**
 * Unhandled workflow failure (no try/catch found).
 */
class WorkflowFailedException(
    message: String,
    cause: Throwable? = null
) : WorkflowException(message, cause) {
    override val type = "workflow"
}

/**
 * Workflow error information (for catch blocks).
 */
data class WorkflowError(
    val type: String,
    val status: Int?,
    val title: String,
    val details: String?
)
```

---

## Serialization Strategy

### JSON Serialization with kotlinx.serialization

**Dependencies** (already in project):
```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
```

**Polymorphic Serialization for State**:

```kotlin
@Serializable
sealed class SerializedMutableState {
    @Serializable
    @SerialName("do")
    data class Do(val state: DoMutableState) : SerializedMutableState()

    @Serializable
    @SerialName("for")
    data class For(val state: ForMutableState) : SerializedMutableState()

    // ... etc
}
```

**Custom Serializers** (if needed):

```kotlin
object NodePositionSerializer : KSerializer<NodePosition> {
    override val descriptor = PrimitiveSerialDescriptor("NodePosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NodePosition) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): NodePosition {
        return NodePosition.parse(decoder.decodeString())
    }
}
```

---

## Migration Strategy

### Phase 1: Parallel Implementation (4-6 weeks)

**Goal**: Build new execution engine alongside existing one

**Tasks**:
1. Create new package: `com.lemline.core.execution2`
2. Implement free functions: `ExecutionOrchestrator.kt`, `ErrorHandler.kt`
3. Implement state classes: `NodeState.kt`, `DoTaskState.kt`, `ForTaskState.kt`, etc.
4. Implement scope: `Scope.kt`, `ScopeBuilder.kt`
5. Update `ExpressionEvaluator` for scope support
6. Implement serialization: `InstanceState.kt`, `StateHydrator.kt`

**Validation**:
- Unit tests for each component
- Integration tests comparing old vs new execution
- Performance benchmarks

### Phase 2: Node Implementation (3-4 weeks)

**Goal**: Implement all node types with new state model

**Tasks**:
1. Create node base class: `Node.kt`
2. Implement flow tasks: `DoTaskNode.kt`, `ForTaskNode.kt`, `SwitchTaskNode.kt`, `TryTaskNode.kt`
3. Implement activity tasks: `SetTaskNode.kt`, `CallHttpTaskNode.kt`, etc.
4. Implement scope building: `buildScope()` method in each node type
5. Implement action execution: `execute()` method in activity nodes

**Validation**:
- Unit tests for each node type
- Test scope building and merging
- Test state initialization and updates

### Phase 3: Runner Integration (2-3 weeks)

**Goal**: Integrate new execution engine with `StepByStepRunner`

**Tasks**:
1. Update `StepByStepRunner` to use `ExecutionOrchestrator`
2. Replace exception-based control flow with return-based
3. Update `InstanceMessageHandler` for new serialization
4. Update outbox patterns for new state model
5. Migrate checkpoint logic to use new consistent state

**Validation**:
- End-to-end tests with real workflows
- Test persistence and resumption
- Test error handling and retry

### Phase 4: Testing and Optimization (2-3 weeks)

**Goal**: Comprehensive testing and performance optimization

**Tasks**:
1. Run full test suite on new implementation
2. Performance testing: throughput, latency, memory
3. Stress testing: large workflows, deep nesting, many iterations
4. Compatibility testing: all supported databases, message brokers
5. Optimization: profile and optimize hot paths

**Validation**:
- All tests pass
- Performance meets or exceeds current implementation
- Memory usage reduced by 60-95% (state compression)

### Phase 5: Deprecation and Cleanup (1-2 weeks)

**Goal**: Remove old implementation

**Tasks**:
1. Remove old `Processor.kt` and related classes
2. Rename `com.lemline.core.execution2` → `com.lemline.core.execution`
3. Update documentation
4. Clean up deprecated code

**Validation**:
- Build succeeds
- All tests pass
- Documentation updated

---

## Testing Strategy

### Unit Tests

**Execution Orchestrator Tests** (`ExecutionOrchestratorTest.kt`):
```kotlin
class ExecutionOrchestratorTest : FunSpec({

    test("enter should skip node if condition is false") {
        val node = createMockNode(ifCondition = ".skip == true")
        val dataset = jsonObject { "skip" to true }

        val result = ExecutionOrchestrator.enter(node, dataset)

        result.next shouldBe node.parent
        result.dataset shouldBe dataset
        result.flowDirective shouldBe FlowDirective.Continue
    }

    test("enter should initialize state and continue") {
        val node = createDoTaskNode(children = 3)
        val dataset = jsonObject { "input" to "data" }

        val result = ExecutionOrchestrator.enter(node, dataset)

        node.state.startedAt shouldNotBe null
        result.next shouldBe node.children[0]
    }

    test("reEnter should update state and advance") {
        val node = createDoTaskNode(children = 3)
        node.state.mutable = DoMutableState(childIndex = 0)

        val dataset = jsonObject { "result" to "data" }
        val result = ExecutionOrchestrator.reEnter(node, dataset, FlowDirective.Continue)

        result.next shouldBe node.children[1]
    }

    test("exitToUp should compute output and return to parent") {
        val node = createActivityTaskNode()
        val dataset = jsonObject { "input" to "data" }

        val result = ExecutionOrchestrator.exitToUp(node, dataset)

        result.next shouldBe node.parent
        result.dataset shouldNotBe null
    }
})
```

**State Tests** (`DoTaskStateTest.kt`, `ForTaskStateTest.kt`, etc.):
```kotlin
class DoTaskStateTest : FunSpec({

    test("shouldExit returns false when more children") {
        val state = DoTaskState(doSize = 3)
        state.mutable = DoMutableState(childIndex = 1)

        state.shouldExit() shouldBe false
    }

    test("shouldExit returns true when all children processed") {
        val state = DoTaskState(doSize = 3)
        state.mutable = DoMutableState(childIndex = 3)

        state.shouldExit() shouldBe true
    }

    test("applyFlowDirective advances to next child") {
        val state = DoTaskState(doSize = 3)
        state.mutable = DoMutableState(childIndex = 0)

        state.applyFlowDirective(FlowDirective.Continue)

        state.mutable.childIndex shouldBe 1
    }

    test("applyFlowDirective jumps to named child") {
        val state = DoTaskState(doSize = 3)
        state.mutable = DoMutableState(childIndex = 0)

        state.applyFlowDirective(FlowDirective.Goto("task2"))

        state.mutable.childIndex shouldBe 2
    }
})
```

### Integration Tests

**Workflow Execution Tests** (`WorkflowExecutionTest.kt`):
```kotlin
class WorkflowExecutionTest : FunSpec({

    test("execute simple sequential workflow") {
        val workflow = parseWorkflow("""
            do:
              - set1:
                  set:
                    step: 1
              - set2:
                  set:
                    step: 2
        """)

        val input = jsonObject {}
        val output = ExecutionOrchestrator.execute(workflow, input)

        output["step"] shouldBe 2
    }

    test("execute workflow with for loop") {
        val workflow = parseWorkflow("""
            do:
              - processItems:
                  for:
                    each: item
                    in: .items
                  do:
                    - process:
                        set:
                          processed: true
        """)

        val input = jsonObject {
            "items" to jsonArray {
                +jsonObject { "id" to 1 }
                +jsonObject { "id" to 2 }
            }
        }

        val output = ExecutionOrchestrator.execute(workflow, input)

        output["processed"] shouldBe true
    }

    test("execute workflow with try/catch") {
        val workflow = parseWorkflow("""
            do:
              - tryTask:
                  try:
                    do:
                      - fail:
                          call: http
                          with:
                            url: http://invalid
                  catch:
                    - handleError:
                        set:
                          error: true
        """)

        val input = jsonObject {}
        val output = ExecutionOrchestrator.execute(workflow, input)

        output["error"] shouldBe true
    }
})
```

### Performance Tests

**Benchmark Tests** (`ExecutionBenchmark.kt`):
```kotlin
class ExecutionBenchmark {

    @Test
    fun benchmarkDeepNesting() {
        // Workflow with 10 nested levels
        val workflow = createDeeplyNestedWorkflow(depth = 10)
        val input = jsonObject {}

        val duration = measureTime {
            ExecutionOrchestrator.execute(workflow, input)
        }

        println("Deep nesting (10 levels): ${duration.inWholeMilliseconds}ms")
    }

    @Test
    fun benchmarkLargeIteration() {
        // Workflow with 1000 iterations
        val workflow = createIterationWorkflow(iterations = 1000)
        val input = jsonObject {
            "items" to jsonArray {
                repeat(1000) { +jsonObject { "id" to it } }
            }
        }

        val duration = measureTime {
            ExecutionOrchestrator.execute(workflow, input)
        }

        println("Large iteration (1000 items): ${duration.inWholeMilliseconds}ms")
    }

    @Test
    fun benchmarkSerialization() {
        // Measure state serialization size
        val instanceState = createLargeInstanceState()

        val serialized = Json.encodeToString(instanceState)
        val size = serialized.length

        println("Serialized state size: $size bytes")
    }
}
```

---

## Performance Considerations

### Memory Optimization

**Current Implementation**:
- Each `NodeInstance` stores full state (position, started time, transformed input)
- ForTask stores entire collection in state
- TryTask stores transformed input for retries
- Estimated: ~150-1000 bytes per node

**New Implementation**:
- Immutable fields cached in-memory (not serialized)
- Only mutable indices and flags serialized
- Collections and inputs recomputed from parent
- Estimated: ~54-66 bytes per node (60-95% reduction)

**Benefits**:
- Smaller message payloads (faster broker transmission)
- Less database storage (for waits, retries, parents)
- Faster serialization/deserialization
- Better horizontal scaling

### CPU Optimization

**Scope Building**:
- Cache scope at node level (reuse for multiple expressions)
- Lazy evaluation: only build scope when needed
- Avoid recursive parent traversal: build scope bottom-up once

**Expression Evaluation**:
- Reuse JQ engine instance (thread-safe pool)
- Cache compiled expressions (if JQ supports)
- Batch evaluations when possible

**State Updates**:
- Use immutable data structures (Kotlin data classes)
- Copy-on-write for state updates
- Minimize allocations in hot paths

### Database Optimization

**State Persistence**:
- Only persist when checkpointing (after successful step)
- Use upsert for state updates (single query)
- Index on workflow ID and position for fast lookups

**Queries**:
- Use prepared statements
- Batch operations where possible
- Connection pooling (already implemented)

---

## Implementation Phases

### Detailed Task Breakdown

#### Phase 1: Parallel Implementation (4-6 weeks)

**Week 1-2: Core Orchestration**
- [ ] Create `com.lemline.core.execution2` package
- [ ] Implement `FlowDirective.kt`
- [ ] Implement `ExecutionOrchestrator.kt` (all free functions)
- [ ] Implement `ErrorHandler.kt` (exception handling)
- [ ] Write unit tests for orchestration

**Week 3-4: State Management**
- [ ] Implement `NodeState.kt` base class
- [ ] Implement `DoTaskState.kt` with mutable separation
- [ ] Implement `ForTaskState.kt` with mutable separation
- [ ] Implement `SwitchTaskState.kt` with mutable separation
- [ ] Implement `TryTaskState.kt` with mutable separation
- [ ] Implement `ActivityTaskState.kt` with mutable separation
- [ ] Write unit tests for all state classes

**Week 5-6: Scope and Serialization**
- [ ] Implement `Scope.kt` and related descriptors
- [ ] Update `ExpressionEvaluator.kt` for scope support
- [ ] Implement `InstanceState.kt` (serializable model)
- [ ] Implement `StateHydrator.kt` (deserialization)
- [ ] Write unit tests for scope building
- [ ] Write unit tests for serialization/deserialization

#### Phase 2: Node Implementation (3-4 weeks)

**Week 1: Base Node**
- [ ] Implement `Node.kt` base class
- [ ] Implement scope building (`buildScope()`)
- [ ] Implement validation methods (`validateInput`, `validateOutput`)
- [ ] Implement transformation methods (`evaluateInput`, `evaluateOutput`)
- [ ] Write unit tests for base node

**Week 2: Flow Tasks**
- [ ] Implement `DoTaskNode.kt`
- [ ] Implement `ForTaskNode.kt` with iteration variables
- [ ] Implement `SwitchTaskNode.kt`
- [ ] Implement `TryTaskNode.kt`
- [ ] Write unit tests for flow tasks

**Week 3-4: Activity Tasks**
- [ ] Implement `SetTaskNode.kt`
- [ ] Implement `CallHttpTaskNode.kt`
- [ ] Implement `EmitTaskNode.kt`
- [ ] Implement `RunTaskNode.kt` (child workflows)
- [ ] Implement `WaitTaskNode.kt`
- [ ] Write unit tests for activity tasks

#### Phase 3: Runner Integration (2-3 weeks)

**Week 1: StepByStepRunner Update**
- [ ] Update `StepByStepRunner` to use `ExecutionOrchestrator`
- [ ] Remove exception-based control flow
- [ ] Update checkpoint logic
- [ ] Write integration tests

**Week 2: Message Handling**
- [ ] Update `InstanceMessageHandler` for new serialization
- [ ] Update `InstanceMessage` model
- [ ] Update deserialization logic
- [ ] Write integration tests

**Week 3: Outbox Updates**
- [ ] Update `WaitOutboxModel` for new state
- [ ] Update `RetryOutboxModel` for new state
- [ ] Update `ParentOutboxModel` for new state
- [ ] Write integration tests

#### Phase 4: Testing and Optimization (2-3 weeks)

**Week 1: Comprehensive Testing**
- [ ] Run all existing tests with new implementation
- [ ] Add integration tests for complex workflows
- [ ] Add tests for edge cases (deep nesting, large iterations)
- [ ] Add tests for error handling (retries, catches)

**Week 2: Performance Testing**
- [ ] Benchmark execution throughput
- [ ] Benchmark serialization size
- [ ] Benchmark memory usage
- [ ] Compare with current implementation

**Week 3: Optimization**
- [ ] Profile hot paths
- [ ] Optimize scope building
- [ ] Optimize state updates
- [ ] Re-run benchmarks

#### Phase 5: Deprecation and Cleanup (1-2 weeks)

**Week 1: Cleanup**
- [ ] Remove old `Processor.kt`
- [ ] Remove old `NodeInstance` classes
- [ ] Rename `execution2` → `execution`
- [ ] Update imports across codebase

**Week 2: Documentation**
- [ ] Update architecture docs
- [ ] Update developer guides
- [ ] Update ADRs
- [ ] Update CLAUDE.md

---

## Success Criteria

### Functional Requirements

✅ All existing workflow features work:
- Sequential execution (do)
- Conditional execution (if)
- Iteration (for)
- Branching (switch)
- Error handling (try/catch)
- Retries
- Activities (set, call, emit, run, wait)

✅ All expression evaluation works:
- Input transformation (`input.from`)
- Output transformation (`output.as`)
- Conditional checks (`if`)
- Scope variables (`$task.*`, `$context`, `$item`, etc.)

✅ State persistence and resumption works:
- Workflows can be paused and resumed
- State is correctly serialized/deserialized
- Waits, retries, and child workflows work

### Performance Requirements

✅ **State Size**: 60-95% reduction in serialized state size
✅ **Throughput**: Match or exceed current implementation (>1000 workflows/sec)
✅ **Latency**: P99 latency < 50ms per step
✅ **Memory**: Reduce memory usage by 50%+

### Quality Requirements

✅ **Test Coverage**: >90% code coverage
✅ **Documentation**: All public APIs documented
✅ **Compatibility**: Works with all supported databases and message brokers
✅ **Backward Compatibility**: Existing workflows can be migrated

---

## Risks and Mitigation

### Risk 1: Breaking Changes

**Risk**: New implementation may break existing workflows

**Mitigation**:
- Parallel implementation (keep old code working)
- Comprehensive test suite
- Gradual migration with feature flags
- Ability to rollback to old implementation

### Risk 2: Performance Regression

**Risk**: New implementation may be slower than current

**Mitigation**:
- Early performance testing
- Continuous benchmarking
- Profiling and optimization
- Performance budgets for key operations

### Risk 3: State Migration

**Risk**: Difficulty migrating in-flight workflows from old to new state format

**Mitigation**:
- Design migration strategy upfront
- Test migration on sample workflows
- Gradual rollout (new workflows use new format, old workflows continue with old)
- Emergency rollback plan

### Risk 4: Complexity

**Risk**: New architecture may be too complex

**Mitigation**:
- Clear documentation
- Code examples and guides
- Developer training
- Regular code reviews

---

## Conclusion

This implementation specification provides a comprehensive plan for migrating Lemline to the functional workflow execution model. The new architecture offers:

1. **Cleaner Separation**: Free functions for orchestration, instance methods for scope-dependent operations
2. **Efficient State Management**: Immutable/mutable separation reduces serialization overhead by 60-95%
3. **Functional Semantics**: Pure function composition with explicit state mutations
4. **Better Error Handling**: State rollback and retry/catch mechanisms
5. **Improved Testability**: Functions are testable in isolation

The migration strategy is designed to minimize risk through parallel implementation, comprehensive testing, and gradual rollout. With an estimated timeline of 12-18 weeks, this represents a significant investment that will pay dividends in:

- Reduced infrastructure costs (smaller messages, less database storage)
- Better horizontal scaling (lighter state, faster serialization)
- Easier maintenance (clearer architecture, better separation of concerns)
- Improved developer experience (functional model, better testability)

**Next Steps**:
1. Review and approve this specification
2. Set up project tracking (tasks, milestones)
3. Begin Phase 1: Parallel Implementation
4. Regular progress reviews and course corrections
