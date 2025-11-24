# Architecture: lemline-runner ↔ lemline-core Integration

**Status**: Implemented
**Last Updated**: 2025-11-11

---

## Overview

Lemline's execution architecture separates **orchestration logic** (lemline-core) from **distributed infrastructure** (
lemline-runner) through two distinct orchestrator implementations.

### Core Principle

> **Orchestrators control workflow execution. Processors contain task-specific logic. State flows externally.**

The orchestrator decides *when* to execute steps and *when* to pause. Processors transform data and execute activities
without knowing about execution modes.

---

## Dual Orchestrator Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     BaseOrchestrator                         │
│  • runStep()      - Execute one workflow step                │
│  • handleException() - Process errors (try/catch/retry)      │
│  • updateRootContext() - Manage context exports              │
│  • completeChildWorkflow() - Handle sub-workflow results     │
└──────────────────────┬────────────────┬─────────────────────┘
                       │                │
        ┌──────────────┴────────┐  ┌────┴──────────────────────┐
        │  CompleteOrchestrator │  │  PausableOrchestrator      │
        └───────────────────────┘  └────────────────────────────┘
         • Executes to completion   • Stops at boundaries
         • Real activities            • Returns pause results
         • Inline sub-workflows       • Enables distributed exec
         • Used for: testing,         • Used for: production,
           single-node execution        multi-worker execution
```

### CompleteOrchestrator

**Purpose**: Synchronous execution from start to finish

**Behavior**:

- Activities execute completely (HTTP calls, shell commands, scripts)
- Delays actually wait using `kotlinx.coroutines.delay()`
- Sub-workflows execute inline recursively (await=true) or fire-and-forget (await=false)
- Returns final workflow output

**Use Cases**:

- Testing workflows without infrastructure
- Single-node deployments
- Development environments

### PausableOrchestrator

**Purpose**: Distributed execution with pause/resume capability

**Behavior**:

- Activities execute completely, **then pause** and return control
- Delays return duration to runner **instead of waiting**
- Sub-workflows throw exception **before execution**, runner handles persistence
- Returns `PausableResult` indicating pause reason or completion

**Use Cases**:

- Production distributed execution
- State persistence at async boundaries
- Multi-worker horizontal scaling

---

## Key Mechanisms

### 1. Exception-Driven Control Flow

Both orchestrators use exceptions for non-linear control flow (similar to try/catch):

**Sub-workflow Invocation**:

```kotlin
// RunWorkflowProcessor always throws (never executes inline)
throw ChildWorkflowRequestedException(
    config = ChildWorkflowConfig(
        namespace = "...",
        name = "...",
        version = "...",
        input = transformedInput,
        awaitCompletion = true/false
    )
)
```

**Orchestrator Response**:

- **CompleteOrchestrator**: Catches exception, executes child inline or async
- **PausableOrchestrator**: Catches exception, captures state, returns `SubWorkflowNeeded`

**Benefits**:

- Input transformation happens before exception (guaranteed correct child input)
- Consistent pattern across error handling and sub-workflows
- Processor stays pure and mode-agnostic

### 2. Stopping Point Detection (PausableOrchestrator)

After each step execution, check for boundaries:

```
┌──────────────────────────────────────────────────┐
│ Execute step via runStep()                       │
│   ↓                                               │
│ Apply state updates                              │
│   ↓                                               │
│ Check stopping conditions:                       │
│   • Activity completed? (CallHTTP, RunShell, etc)│
│   • Delay needed? (result.delay > 0)             │
│   • Sub-workflow? (ChildWorkflowRequestedException)│
│   ↓                                               │
│ If stopping point:                               │
│   → Capture state                                │
│   → Return PausableResult                        │
│ Else:                                            │
│   → Continue to next step                        │
└──────────────────────────────────────────────────┘
```

**State Consistency Guarantee**: All pauses happen *after* step completes, ensuring:

- Activity output is available
- State updates are applied
- No partial execution state

### 3. Delay Handling

**CompleteOrchestrator**:

```kotlin
if (result.delay?.isPositive() == true) {
    delay(result.delay)  // Actually wait
}
```

**PausableOrchestrator**:

```kotlin
if (result.delay?.isPositive() == true) {
    return PausableResult.WaitNeeded(
        nextNode = current,
        states = states,
        output = input,
        duration = result.delay
    )
}
```

Processors (WaitTask, retry logic) return delay duration in step result. Orchestrator decides whether to wait or pause.

### 4. Sub-workflow Execution

**CompleteOrchestrator**:

```kotlin
catch (e: ChildWorkflowRequestedException) {
    if (e.config.awaitCompletion) {
        // Execute inline, wait for result
        val childOutput = run(childNode, e.config.input)
        // Continue with child output
    } else {
        // Fire-and-forget: launch coroutine
        launch { run(childNode, e.config.input) }
        // Continue with parent input
    }
}
```

**PausableOrchestrator**:

```kotlin
// When child workflow requested
catch (e: ChildWorkflowRequestedException) {
    return PausableResult.SubWorkflowNeeded(
        nodePosition = current.reference,
        states = states,
        childConfig = e.config
    )
}

// When runner needs to resume parent (called by runner)
fun resumeFromChildWorkflow(
    node: Node<*>,         // From SubWorkflowNeeded.nodePosition
    childOutput: JsonElement,  // await=true: child's output; await=false: child's input
    states: MutableStates     // From SubWorkflowNeeded.states
): PausableResult {
    // Complete task with output
    val result = completeChildWorkflowTask(node, childOutput, states)
    // Continue execution (may pause again or complete)
    return run(result.nextNode, result.dataset, states)
}
```

Runner handles both initiation and resumption:

- **await=true**: Save parent state → start child → when child completes →
  `resumeFromChildWorkflow(node, childOutput, states)`
- **await=false**: Start child immediately → `resumeFromChildWorkflow(node, childConfig.input, states)` (parent
  continues with child's input)

---

## Pausable Result Types

```kotlin
sealed class PausableResult {
    // Workflow completed successfully
    data class WorkflowCompleted(val output: JsonElement)

    // Activity executed, pause to persist state
    data class ActivityCompleted(
        val nextNode: Node<*>?,
        val states: States,
        val output: JsonElement
    )

    // Delay needed, pause to schedule timer
    data class WaitNeeded(
        val nextNode: Node<*>?,
        val states: States,
        val output: JsonElement,
        val duration: Duration
    )

    // Retry needed with backoff
    data class RetryNeeded(
        val nextNode: Node<*>?,
        val states: States,
        val output: JsonElement,
        val duration: Duration,
        val exception: WorkflowException
    )

    // Sub-workflow requested, pause to handle parent-child
    data class SubWorkflowNeeded(
        val nodePosition: String,
        val states: States,
        val childConfig: ChildWorkflowConfig
    )
}
```

---

## Runner Integration

**StepByStepRunner** uses PausableOrchestrator and handles pause results:

```
┌─────────────────────────────────────────────────┐
│ Receive InstanceMessage from commands-in       │
│   ↓                                              │
│ Deserialize: position, states, workflow def     │
│   ↓                                              │
│ PausableOrchestrator.run(node, dataset, states) │
│   ↓                                              │
│ Match PausableResult:                           │
│   • Complete           → Emit completion event  │
│   • ActivityCompleted  → Emit to commands-out  │
│   • WaitNeeded         → Insert lemline_waits   │
│   • RetryNeeded        → Insert lemline_retries │
│   • SubWorkflowNeeded  → Handle parent/child    │
└─────────────────────────────────────────────────┘
```

**Key Insight**: Runner never executes workflow logic. It only:

1. Deserializes state
2. Calls PausableOrchestrator
3. Handles pause results via infrastructure (Kafka, database, outbox)

---

## State Management

### External State Model

State is **never stored inside nodes**. It flows as parameters:

```kotlin
val states: MutableMap<Node<*>, NodeState> = mutableMapOf()
val output = orchestrator.run(rootNode, input, states)
```

**Benefits**:

- Pure functional execution
- Easy serialization for persistence
- State consistency guaranteed by design
- No hidden mutations

### State Contents

```kotlin
data class InstanceMessage(
    val position: NodePosition,           // Where we are in workflow tree
    val states: Map<Node<*>, NodeState>,  // Only non-empty states
    val workflowId: UUID,                 // Workflow instance ID
    val namespace: String,                // Workflow definition reference
    val name: String,
    val version: String
)
```

Compressed and sent via Kafka messages. Any worker can deserialize and continue execution.

---

## Benefits

### Clean Separation

- **lemline-core**: Pure orchestration, no infrastructure dependencies
- **lemline-runner**: Infrastructure only, no workflow logic

### Testability

- Test CompleteOrchestrator without Kafka/database
- Test PausableOrchestrator with mock infrastructure
- Integration tests only at runner level

### Type Safety

- Pause points explicit in code (not hidden in configuration)
- Compiler enforces state consistency
- No runtime mode switching surprises

### Horizontal Scaling

- Stateless workers (state in messages)
- Any worker can process any message
- Natural backpressure via Kafka consumer groups

---

## Migration from Old Architecture

**Before**: Single ExecutionOrchestrator with mode parameter
**After**: Two specialized orchestrators (Complete and Pausable)

**Changes**:

1. Renamed `ExecutionOrchestrator` → `BaseOrchestrator` (shared logic)
2. Created `CompleteOrchestrator` (was default behavior)
3. Created `PausableOrchestrator` (new pausable behavior)
4. Moved delay handling from processors to orchestrators
5. Changed sub-workflow execution to exception-based control flow

**Rationale**: Separate implementations are clearer, simpler, and easier to maintain than conditional logic in a single
class.

---

## References

**Implementation**:

- `lemline-core/src/main/kotlin/com/lemline/core/execution/BaseOrchestrator.kt`
- `lemline-core/src/main/kotlin/com/lemline/core/execution/complete/CompleteOrchestrator.kt`
- `lemline-core/src/main/kotlin/com/lemline/core/execution/pausable/PausableOrchestrator.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt`

**Tests**:

- `lemline-core/src/test/kotlin/com/lemline/core/execution/complete/CompleteOrchestratorTest.kt`
- `lemline-core/src/test/kotlin/com/lemline/core/execution/pausable/PausableOrchestratorTest.kt`
