# Architecture: lemline-runner ↔ lemline-core Integration

**Status**: Design Complete - Ready for Implementation
**Date**: 2025-11-11
**Context**: Refactoring integration between lemline-core's new functional execution model and lemline-runner's distributed execution infrastructure

---

## Executive Summary

**Problem**: The lemline-runner needs to execute workflows in a distributed manner, pausing at specific boundaries (activities, waits, sub-workflows) to persist state and resume in different workers.

**Solution**: Split the orchestrator into two implementations:
- **CompleteOrchestrator**: Executes workflows to completion (current behavior, for testing and synchronous execution)
- **PausableOrchestrator**: Detects stopping points and returns control to runner for persistence

**Key Decisions**:
1. **Separate orchestrator classes** (not mode parameter) - clearer intent, simpler implementations
2. **Delay handling in orchestrator** - WaitProcessor returns immediately, orchestrator decides whether to actually delay
3. **Exception-based sub-workflow control** - RunWorkflowProcessor throws exception (same pattern as try/catch), orchestrator catches and handles
4. **Sub-workflow exception for both await modes** - Simplifies tracking and enables monitoring of fire-and-forget workflows
5. **ExecutionContext abstraction deferred** - Focus on pause/resume problem first, infrastructure abstraction is separate concern for Phase 2

**Implementation**: 10-step plan focusing on core orchestrator refactoring, no infrastructure changes needed yet.

---

## Background

The lemline-core module has been refactored to use a **task-by-task functional execution model** with:
- Immutable node definitions (`Node<T>`)
- External state management (`Map<Node<*>, NodeState?>`)
- Pure functional execution with coroutines
- Ability to resume from any state

The lemline-runner needs to integrate with this new model to provide:
- Distributed execution across workers
- State persistence at async boundaries
- Outbox pattern for durable operations (waits, retries, parent-child workflows)

**Key Requirement**: Runner must execute tasks sequentially until completing the first "true" activity (HTTP call, shell/script execution) or hitting an async boundary (wait, retry with delay), then stop and persist state.

---

## Core Architectural Decisions

### 1. ExecutionContext Pattern: Infrastructure Abstraction

**Principle**: Separate orchestration logic from infrastructure execution.

The core module defines an interface representing execution capabilities:
- HTTP calls
- Shell/script execution
- Sub-workflow invocation
- Time delays

Different implementations provide different behaviors:
- **Synchronous Context**: Actually executes (makes real HTTP calls, waits on delays)
- **Mock Context**: Returns fake data for testing
- **Runner Context**: Delegates to runner infrastructure (Kafka, outbox, etc.)

**Benefits**:
- lemline-core has zero infrastructure dependencies
- Testing doesn't require test containers or network
- Clean hexagonal/ports-and-adapters architecture
- Multiple execution strategies possible

**Key Insight**: The context doesn't know about execution modes - it simply provides capabilities. The orchestrator decides when to use them and when to pause.

---

### 2. Separate Orchestrator Implementations

**Principle**: Different execution behaviors deserve separate, focused implementations.

Two orchestrator implementations sharing common execution logic:

**CompleteOrchestrator** (current behavior):
- Executes workflow from start to finish
- Activities execute via context (real HTTP calls, shell commands)
- Delays actually wait using coroutines
- Sub-workflows execute inline recursively
- Returns final workflow output
- Used for: Synchronous testing, single-node execution

**PausableOrchestrator** (new for distributed runner):
- Executes activities via context (actually makes HTTP calls!)
- After activity completes, stops and returns control to runner
- When delay needed, stops instead of waiting
- Before sub-workflow, stops to allow parent-child persistence
- Returns pause result (activity completed, delay needed, sub-workflow needed, or complete)
- Used for: Distributed execution with state persistence

**Design Rationale**:
- Separate classes provide **clearer intent** through naming
- Each implementation is **simpler** without mode conditionals in the main loop
- **Different return types** - CompleteOrchestrator returns workflow output, PausableOrchestrator returns pause/complete results
- **Shared base class** provides common helpers (`runStep()`, `captureState()`, `handleException()`)
- Better testability - test each behavior independently without mode parameters
- Easier maintenance - changes to pausable logic don't affect complete logic

---

### 3. Orchestrator-Controlled Pausing

**Principle**: PausableOrchestrator inspects execution state after each step and decides whether to continue or pause.

**CompleteOrchestrator's run loop** (simplified):
1. Execute one step via `runStep()` (which may call context methods)
2. Apply state updates from the step result
3. If step result contains delay, call `context.delay()` to actually wait
4. Continue to next step
5. Repeat until workflow completes

**PausableOrchestrator's run loop** (with pause checks):
1. Execute one step via `runStep()` (which may call context methods)
2. Apply state updates from the step result
3. **Check if we hit a stopping point** (see below)
4. **If stopping point**: Capture consistent state and return pause result
5. **If not stopping**: Continue to next step
6. Repeat until stopping point or workflow completes

**Stopping Point Detection** (after step execution in PausableOrchestrator):
- Did we just complete an activity? → Check if current node is activity type
- Do we need to delay? → Check if step result contains delay duration
- Is next step a sub-workflow? → Check if next node is sub-workflow type

**Key Insight**: All checks happen AFTER the step completes, ensuring state consistency. We never pause mid-step (except sub-workflows - see open questions).

---

### 4. State Management Strategy

**Principle**: State capture must guarantee consistency and correct resumption.

**State Capture Timing**: After activity execution completes
- Activity has fully executed (HTTP response received, shell command finished)
- State updates from the step have been applied
- Output is available and can be saved
- No risk of partial state

**State Contents** (for persistence):
- Current position in workflow tree
- Map of node states (only non-empty states)
- Current dataset (workflow data)
- Workflow metadata (namespace, name, version, id)

**Resumption Strategy**:
- Deserialize saved state
- Reconstruct node tree from definition
- Apply saved states to nodes
- Continue from saved position with saved dataset

**Guarantee**: State captured by PausableOrchestrator can always resume in new worker (using new PausableOrchestrator instance) and continue execution.

---

### 5. Handling Different Stopping Points

#### Activity Completion (HTTP, Shell, Script)

**Flow**:
1. PausableOrchestrator calls processor's `execute()`
2. Processor calls `context.callHttp()` (or shell/script)
3. Context **actually executes** the activity (makes HTTP call)
4. Activity returns result
5. Processor transforms output and returns
6. PausableOrchestrator applies state updates
7. PausableOrchestrator detects activity completed
8. PausableOrchestrator captures state (includes activity output)
9. Returns pause result to runner

**Runner Action**: Update instance message with new state, emit to workflows-out channel for next worker to continue.

**Position After Stop**: Current position is at the activity node (or parent node having received child output - TBD based on node navigation model).

#### Delay Required (Wait Task or Retry with Delay)

**Flow**:
1. For wait tasks: Processor returns immediately without calling `context.delay()`
2. For retry: Error handler returns step result with delay field populated
3. PausableOrchestrator receives result with delay duration
4. PausableOrchestrator detects delay > 0
5. PausableOrchestrator captures state
6. Returns pause result with delay duration

**In CompleteOrchestrator**: Calls `context.delay()` which actually suspends the coroutine for the specified duration.

**Runner Action**: Create outbox entry in `lemline_waits` table with delayed_until timestamp. Outbox processor will resume workflow after delay expires.

**Position After Stop**: Current position where delay was needed (wait task or retry attempt).

#### Sub-workflow Invocation

**Status**: ⚠️ **OPEN QUESTION** - This case is special and requires discussion.

**The Challenge**: Sub-workflows present a "mid-step" stopping point problem:
- Need to execute input transformation (happens at node entry)
- Need to stop before actually calling `context.runWorkflow()`
- But input transformation and workflow execution are in same processor

**This forces us to either**:
1. Stop before entering the node (lose input transformation)
2. Stop during processor execution (mid-step pause)
3. Make processor mode-aware (breaks clean separation)

See "Open Questions" section below for detailed analysis.

---

## Benefits of This Architecture

### Clean Separation of Concerns
- **lemline-core**: Pure orchestration, stateless workers, functional execution
- **lemline-runner**: Infrastructure, persistence, message passing, scheduling

### Testability Without Infrastructure
- Core tests use mock context, no Kafka/database needed
- Can test PAUSABLE mode behavior with mocks
- Integration tests only at runner level

### Multiple Execution Strategies
- Synchronous: For testing and single-node deployments
- Pausable: For distributed execution
- Future: Dry-run, tracing, debugging modes

### Type Safety and Correctness
- State consistency guaranteed by design
- Pause points explicitly checked by orchestrator
- No hidden control flow

### Flexibility for Future Features
- Easy to add new activity types (just extend context interface)
- Easy to add new stopping conditions (add checks in orchestrator)
- Easy to implement different runners (different context implementations)

---

## Finalized Approach: Exception-Based Sub-workflow Handling

### Sub-workflow Exception Pattern (Inspired by Try/Catch)

The refactored lemline-core already uses exception-driven control flow for error handling (try/catch/retry). We apply the **same pattern** to sub-workflow invocation.

#### Core Principle

**RunWorkflowProcessor throws exception instead of executing inline**:
- Input transformation happens before exception (ensures correct child input)
- Exception contains all context needed (childId, namespace, name, version, input, awaitCompletion flag)
- Orchestrator catches and handles based on its type
- State updates are explicit and atomic (same as error handling)

#### Exception Definition

```kotlin
class ChildWorkflowStartedException(
    val childId: UUID,
    val namespace: WorkflowNamespace,
    val name: WorkflowName,
    val version: WorkflowVersion,
    val input: JsonElement,
    val awaitCompletion: Boolean  // true for await, false for fire-and-forget
) : RuntimeException()
```

#### RunWorkflowProcessor Implementation

```kotlin
class RunWorkflowProcessor {
    suspend fun execute(input: JsonElement, scope: Scope): JsonElement {
        // Transform input according to workflow's input.from expression
        val transformedInput = applyInputTransform(input, scope)

        // ALWAYS throw exception (for both await=true AND await=false)
        // Orchestrator decides how to handle based on awaitCompletion flag
        throw ChildWorkflowStartedException(
            childId = UUIDv7.generate(),
            namespace = extractNamespace(),
            name = extractName(),
            version = extractVersion(),
            input = transformedInput,
            awaitCompletion = workflowConfig.await
        )
    }
}
```

**Key Decision**: Exception thrown for **both await=true and await=false**
- Simplifies processor logic (one code path)
- Enables runner to track all child workflows (even fire-and-forget) for monitoring
- Orchestrator controls execution behavior via awaitCompletion flag

#### CompleteOrchestrator Handling

```kotlin
while (current != null) {
    try {
        val result = runStep(current, input, states)
        // ... normal flow
    } catch (e: ChildWorkflowStartedException) {
        if (e.awaitCompletion) {
            // Execute child inline and wait for completion
            val childOutput = CompleteOrchestrator().run(
                loadWorkflowDefinition(e.namespace, e.name, e.version),
                e.input
            )
            input = applyOutputTransform(childOutput)
            current = current.parent
        } else {
            // Fire-and-forget: launch coroutine, parent continues immediately
            CoroutineScope(Dispatchers.IO).launch {
                CompleteOrchestrator().run(
                    loadWorkflowDefinition(e.namespace, e.name, e.version),
                    e.input
                )
            }
            input = input  // Parent continues with original input
            current = current.parent
        }
    }
}
```

#### PausableOrchestrator Handling

```kotlin
while (current != null) {
    try {
        val result = runStep(current, input, states)
        states.updateWith(result.stateUpdates)

        // Check other pause points (activities, delays)...

    } catch (e: ChildWorkflowStartedException) {
        // Create state to track parent-child relationship
        val parentState = RunWorkflowState(
            startedAt = Instant.now(),
            transformedInput = input,
            childId = e.childId,
            childNamespace = e.namespace,
            childName = e.name,
            childVersion = e.version,
            childInput = e.input,
            awaitCompletion = e.awaitCompletion
        )
        states[current] = parentState

        return PausableResult.SubWorkflowStarted(
            parentState = captureState(),
            childConfig = e
        )
    }
}
```

**Runner Integration**: StepByStepRunner handles both cases:
- **await=true**: Create parent outbox entry + emit child message, wait for child completion
- **await=false**: Emit child message + immediately emit parent continuation (no waiting)

### Why This Approach Works

**✅ Consistent with existing patterns**: Same exception-driven control flow as try/catch/retry
**✅ Input transformation guaranteed**: Happens before exception is thrown
**✅ State consistency maintained**: State updates are explicit and atomic
**✅ Step-by-step model preserved**: Each runStep() is still atomic, exception is alternative control flow
**✅ Processor stays pure**: No mode awareness, just transforms and throws
**✅ Orchestrator controls behavior**: Different handling per orchestrator type
**✅ Supports both await modes**: Single code path with flag-based behavior
**✅ Enables runner tracking**: All child workflows can be monitored/debugged

### Comparison to Try/Catch Pattern

| Aspect | Try/Catch/Retry | Sub-workflow |
|--------|-----------------|--------------|
| Exception type | `WorkflowException` | `ChildWorkflowStartedException` |
| State type | `TryState` | `RunWorkflowState` |
| Thrown by | Any task encountering error | `RunWorkflowProcessor` |
| Caught by | `ExecutionOrchestrator.handleException()` | Orchestrator catch block |
| State updates | Explicit via `updatesToCleanState()` | Explicit via state map update |
| Serializable | ✓ (for retry across messages) | ✓ (for parent-child coordination) |

### Open Questions Resolved

**Position tracking after activity**: To be validated against current node navigation model during implementation.

**Fire-and-forget tracking**: Exception thrown for both modes enables runner to track all child workflows. Runner decides whether to persist parent tracking based on awaitCompletion flag.

---

## Implementation Plan

### Phase 1: Core Orchestrator Refactoring (Incremental & Testable)

Each step produces a clean, testable state before moving to the next.

#### Step 1: Change Wait/Delay Model

**Goal**: Move delay handling from WaitProcessor to ExecutionOrchestrator

**Changes**:
1. Modify `WaitProcessor.execute()`:
   - Remove `delay()` call
   - Return immediately with input
   - Duration already available in WaitTask configuration

2. Modify `ExecutionOrchestrator`:
   - After calling `runStep()`, check if `result.delay != null`
   - If delay exists: call `kotlinx.coroutines.delay(result.delay)`
   - Continue to next step after delay completes

3. Update tests:
   - Verify WaitProcessor doesn't actually delay
   - Verify ExecutionOrchestrator handles delays correctly
   - Ensure wait workflows still work end-to-end

**Result**: Clean, testable state where orchestrator controls all delays. All existing tests should pass.

---

#### Step 2: Change Sub-workflow Model

**Goal**: Move sub-workflow execution control to ExecutionOrchestrator using exception pattern

**Changes**:
1. Create `ChildWorkflowStartedException`:
   - Include: childId, namespace, name, version, input, awaitCompletion flag
   - Extends RuntimeException

2. Create `RunWorkflowState`:
   - Track: childId, namespace, name, version, input, awaitCompletion
   - Serializable for future persistence needs

3. Modify `RunWorkflowProcessor.execute()`:
   - Transform input (as before)
   - Always throw `ChildWorkflowStartedException` (both await=true and await=false)
   - Remove direct call to ExecutionOrchestrator.run()

4. Modify `ExecutionOrchestrator`:
   - Add catch block for `ChildWorkflowStartedException`
   - If await=true: Execute child inline with new ExecutionOrchestrator instance
   - If await=false: Launch coroutine, parent continues immediately
   - Apply output transformation after child completes

5. Update tests:
   - Verify sub-workflow input transformation happens before exception
   - Verify await=true sub-workflows execute inline correctly
   - Verify await=false sub-workflows launch and parent continues
   - Ensure all sub-workflow tests pass

**Result**: Clean, testable state where orchestrator controls all sub-workflow execution. Exception pattern proven to work.

---

#### Step 3: Introduce Pausable Orchestrator

**Goal**: Create PausableOrchestrator that can stop at boundaries without executing to completion

**Changes**:
1. Create `PausableResult` sealed class:
   - `ActivityCompleted(state, output)` - After HTTP/Shell/Script
   - `DelayNeeded(state, duration)` - For waits and retries
   - `SubWorkflowStarted(state, childConfig)` - For child workflows
   - `Complete(output)` - Workflow finished

2. Extract `BaseOrchestrator`:
   - Common helpers: `runStep()`, `captureState()`, `handleException()`
   - Shared state management logic
   - No changes to existing behavior yet

3. Rename `ExecutionOrchestrator` → `CompleteOrchestrator`:
   - Extend `BaseOrchestrator`
   - Keep all current behavior (delays inline, sub-workflows via exception)
   - No functional changes, just renamed

4. Create `PausableOrchestrator`:
   - Extend `BaseOrchestrator`
   - After each `runStep()`, check for stopping points:
     - Activity: `if (current.isActivity()) return PausableResult.ActivityCompleted(...)`
     - Delay: `if (result.delay != null) return PausableResult.DelayNeeded(...)`
   - Catch `ChildWorkflowStartedException`:
     - Create and store `RunWorkflowState`
     - Return `PausableResult.SubWorkflowStarted(...)`
   - Return type: `PausableResult`

5. Update tests:
   - Test CompleteOrchestrator still works as before (all existing tests)
   - Test PausableOrchestrator stops at each boundary type
   - Verify state capture is consistent
   - Test both await=true and await=false with PausableOrchestrator

**Result**: Two working orchestrator implementations, fully tested, ready for runner integration.

---

#### Step 4: Integrate with Runner (lemline-runner module)

**Goal**: Update StepByStepRunner to use PausableOrchestrator

**Changes**:
1. Update `StepByStepRunner.kt`:
   - Replace broken Processor references with PausableOrchestrator
   - Handle each PausableResult variant:
     - `ActivityCompleted` → emit continuation message to workflows-out
     - `DelayNeeded` → create wait outbox entry
     - `SubWorkflowStarted` → create parent outbox + child message (or just child for fire-and-forget)
     - `Complete` → emit completion event

2. Update tests:
   - Integration tests with real workflows
   - Verify state persistence and resumption
   - Test outbox creation for each pause type

**Result**: Full integration complete, distributed execution working.

### Phase 2: ExecutionContext Abstraction (Future)

This phase improves testability and infrastructure separation. Can be implemented independently later.

**Goals**:
- Abstract activity execution behind `ExecutionContext` interface
- Create different context implementations (Synchronous, Mock, Runner)
- Enable testing without real HTTP calls, shell execution, etc.
- Cleaner separation between orchestration logic and infrastructure

**Not required for solving the current pause/resume problem** - this is an orthogonal concern focused on testability and architecture cleanliness.

---

## References

- Refactored execution model: `/lemline-core/src/main/kotlin/com/lemline/core/execution/ExecutionOrchestrator.kt`
- Current (broken) runner integration: `/lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt`
- Node execution: `/lemline-core/src/main/kotlin/com/lemline/core/nodes/NodeInstance.kt`
