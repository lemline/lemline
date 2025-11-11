# PausableOrchestrator Tests

Tests for **PausableOrchestrator** - the workflow orchestrator that pauses at specific boundaries for distributed execution.

## What is PausableOrchestrator?

`PausableOrchestrator` executes workflows step-by-step and pauses at boundaries:
- ⏸️ **Pauses after activities**: Stops after HTTP, Shell, Script execution completes
- ⏸️ **Pauses on delays**: Returns duration instead of waiting
- ⏸️ **Pauses before sub-workflows**: Returns config instead of executing inline
- 📤 **Returns PausableResult**: Indicates pause reason or completion
- 🔄 **Distributed execution**: Designed for multi-worker deployments with state persistence

## Test Files

### Core Orchestrator Tests
- **`PausableOrchestratorTest.kt`** (13 tests) - Comprehensive pause behavior tests

## Test Categories

### 1. Activity Pause Tests
Verify orchestrator pauses after activities complete:
- ✅ HTTP calls (GET, POST, etc.)
- ✅ Shell commands
- ✅ Script execution (when available)

### 2. Delay Pause Tests
Verify orchestrator pauses instead of waiting:
- ✅ Wait tasks with second delays
- ✅ Wait tasks with millisecond delays
- ✅ Retry delays with backoff

### 3. Sub-workflow Pause Tests
Verify orchestrator pauses before sub-workflows:
- ✅ await=true (parent waits for child)
- ✅ await=false (fire-and-forget)

### 4. Completion Tests
Verify orchestrator completes without pausing when appropriate:
- ✅ Workflows with only non-activity tasks (Set, Do, etc.)

### 5. Sequential Execution Tests
Verify orchestrator executes multiple non-activity steps before pausing:
- ✅ Multiple Set tasks before first activity
- ✅ State accumulation across steps

### 6. State Consistency Tests
Verify state capture at pause points:
- ✅ All intermediate states captured
- ✅ Loop iterations preserved
- ✅ Transformations included

## Running Tests

```bash
# Run all pausable orchestrator tests
./gradlew :lemline-core:test --tests "com.lemline.core.execution.pausable.*"

# Run specific test
./gradlew :lemline-core:test --tests "com.lemline.core.execution.pausable.PausableOrchestratorTest.should pause after HTTP activity completes"
```

## PausableResult Types

The orchestrator returns one of five result types:

| Result Type | When Returned | Contains |
|-------------|---------------|----------|
| `Complete(output)` | Workflow finished | Final output |
| `ActivityCompleted(nextNode, states, output)` | After HTTP/Shell/Script | Next node + state + activity output |
| `WaitNeeded(nextNode, states, duration)` | Wait task | Next node + state + wait duration |
| `RetryNeeded(nextNode, states, duration)` | Retry with backoff | Next node + state + backoff duration |
| `SubWorkflowNeeded(nextNode, states, config, await)` | Before sub-workflow | Next node + state + child config + sync/async flag |

## Test Coverage

Total: **13 tests** covering:
- ✅ All pause points (activities, delays, sub-workflows)
- ✅ Both sub-workflow modes (await & fire-and-forget)
- ✅ Non-pausing scenarios (pure computation)
- ✅ Sequential execution before pause
- ✅ State consistency at pause points

## Key Characteristics

| Aspect | Behavior |
|--------|----------|
| **Execution Mode** | Step-by-step with pauses |
| **Return Type** | `PausableResult` (pause or complete) |
| **Activities** | Execute then pause |
| **Delays** | Pause and return duration |
| **Sub-workflows** | Pause and return config |
| **State** | Captured at each pause point |
| **Use Case** | Distributed execution, state persistence |

## Pause Point Detection

The orchestrator checks for pause points **after each step completes**:

```kotlin
1. Execute step via runStep()
2. Apply state updates
3. Check stopping conditions:
   - Is current node an activity? → ActivityCompleted
   - Does result contain delay? → DelayNeeded
   - Did ChildWorkflowStartedException throw? → SubWorkflowStarted
4. If no stop: Continue to next step
5. If stop: Capture state and return PausableResult
```

## State Consistency Guarantees

All pauses happen **after** the step completes:
- ✅ Activity has fully executed (response received)
- ✅ State updates have been applied
- ✅ Output is available
- ✅ No partial state risk

## Comparison with CompleteOrchestrator

For tests that verify **complete execution**, see `../complete/README.md`.

| Feature | CompleteOrchestrator | PausableOrchestrator |
|---------|---------------------|---------------------|
| Waits on delays | ✅ Yes | ❌ No (pauses) |
| Executes sub-workflows inline | ✅ Yes | ❌ No (pauses) |
| Returns on activity | ❌ No (continues) | ✅ Yes (pauses) |
| Return type | `JsonElement` | `PausableResult` |
| Best for | Testing, sync execution | Distributed execution |

## Integration with Runner

The `PausableResult` is designed for integration with `lemline-runner`:

```kotlin
when (val result = PausableOrchestrator.run(node, input, states)) {
    is PausableResult.Complete ->
        // Emit completion event
    is PausableResult.ActivityCompleted ->
        // Update instance message, emit to workflows-out
    is PausableResult.DelayNeeded ->
        // Create wait outbox entry
    is PausableResult.SubWorkflowStarted ->
        // Create parent outbox + child message
}
```

See `docs/dev/runner-core-integration-architecture.md` for full integration details.
