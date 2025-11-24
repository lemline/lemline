# Fork and Parallel Branches

## Overview

`ForkTask` enables parallel execution of multiple branches. Fork acts as an error boundary.

## Key Files

| File | Purpose |
|------|---------|
| `processors/ForkProcessor.kt` | Fork processor |
| `states/ForkState.kt` | Fork state |
| `orchestrator/StepByStepOrchestrator.kt` | Branch detection |
| `utils/Branching.kt` | Parallel utilities |

---

## Fork DSL

```yaml
fork:
  branches:
    - name: branch1
      do:
        - task1: ...
    - name: branch2
      do:
        - task2: ...
  compete: false  # true = first wins, false = wait all
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `branches` | `List<Branch>` | required | Parallel branches |
| `compete` | `Boolean` | `false` | First branch wins vs wait all |

---

## Execution Modes

### Cooperative (compete: false)

All branches execute, results collected as array:

```
Input → [branch1, branch2] → Output: [result1, result2]
```

**Use cases**: Parallel API calls, fan-out/fan-in, aggregation

### Compete (compete: true)

First branch to complete wins, others cancelled:

```
Input → [branch1, branch2] → Output: result1 (first success)
```

**Use cases**: Racing providers, timeout fallbacks, redundancy

---

## ForkProcessor

```kotlin
class ForkProcessor(node: Node<ForkTask>) : NodeProcessor<ForkTask, ForkState> {
    override fun getNextStepInfo(...): NextStepInfo<ForkState> {
        // Immediately signal orchestrator
        throw ForkStartedException(state = state, transformedInput = dataset)
    }
}
```

Orchestrator receives exception and:
1. Extracts branches from `node.children`
2. Creates `WorkflowCommand` for each branch
3. Executes branches concurrently
4. Collects results based on compete mode

---

## Fork as Error Boundary

Errors in branches **stop at the ForkTask**:

| Mode | One Branch Fails | All Fail |
|------|------------------|----------|
| Cooperative | Fork fails | Fork fails |
| Compete | Continues (waits for success) | Fork fails |

```kotlin
fun forkBranchFailed(position, taskStates, error): BranchFailed? {
    // Walk up to find ForkTask
    var current = position
    while (current != NodePosition.root) {
        val node = workflow.getNode(current.parent)
        if (node.task is ForkTask) {
            return BranchFailed(
                taskStates = cleanStates(taskStates, current),
                branchIndex = extractBranchIndex(current),
                error = error
            )
        }
        current = current.parent
    }
    return null  // Error propagates
}
```

---

## Branch Detection

### Completion

```kotlin
fun forkBranchCompleted(position, taskStates, output): BranchCompleted? {
    // Walk up to find ForkTask
    // Return BranchCompleted with cleaned states
}
```

### State Cleanup

```kotlin
fun cleanStates(taskStates: TaskStates, branchPosition: NodePosition): TaskStates =
    taskStates.filterKeys { !it.startsWith(branchPosition) }
```

---

## Parallel Utilities

```kotlin
// Cooperative: all branches, fail on first error
suspend fun <T, R> List<T>.mapAwaitAllFailFast(transform: suspend (T) -> R): List<R>

// Compete: race branches, return first success
suspend fun <T, R> List<T>.mapAwaitFirstFailSlow(transform: suspend (T) -> R): R
```

---

## Testing

### Cooperative Mode

```kotlin
@Test
fun `should execute all branches`() = runTest {
    val yaml = """
        fork:
          branches:
            - name: b1
              do:
                - set1: { set: { x: 1 } }
            - name: b2
              do:
                - set2: { set: { x: 2 } }
    """
    val result = executeWorkflow(yaml)
    assertTrue(result is JsonArray)
    assertEquals(2, result.size)
}
```

### Compete Mode

```kotlin
@Test
fun `should return first result`() = runTest {
    val yaml = """
        fork:
          compete: true
          branches:
            - name: fast
              do:
                - setFast: { set: { result: "fast" } }
            - name: slow
              do:
                - wait: { wait: PT1S }
                - setSlow: { set: { result: "slow" } }
    """
    val result = executeWorkflow(yaml)
    assertEquals("fast", result["result"])
}
```

### Error Boundary

```kotlin
@Test
fun `cooperative fails if any branch fails`() = runTest {
    val yaml = """
        fork:
          branches:
            - name: failing
              do:
                - raise: { error: { type: runtime, status: 500, title: "Fail" } }
            - name: success
              do:
                - set: { set: { ok: true } }
    """
    assertThrows<WorkflowException> { executeWorkflow(yaml) }
}
```

---

## Common Issues

| Issue | Check |
|-------|-------|
| Branch not executing | `branches` has `name` and `do` |
| Wrong result type | Cooperative → array, compete → single |
| Error not contained | Error must be inside branch |
| State leaking | `cleanStates` removes branch states |
