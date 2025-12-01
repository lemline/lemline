# Plan: ActivityStarted Event Model

## Goal

Extend the async event model to all activities. Processors return `ActivityStarted` events instead of executing inline.
This separates orchestration (what to do) from execution (how to do it).

## Motivation

1. **Infrastructure isolation**: `lemline-core` becomes truly I/O-free
2. **Testability**: `FullOrchestrator` can use mock executors for unit tests
3. **Consistency**: All activities follow the same pattern
4. **Flexibility**: Runner can implement activity-specific timeouts, circuit breakers, routing

## Event Hierarchy

```
WorkflowEvent
├── Outcome
│   ├── WorkflowCompleted
│   ├── WorkflowFailed
│   ├── ForkBranchCompleted
│   └── ForkBranchFailed
├── Suspension (requires async coordination)
│   ├── WaitStarted         -- timer scheduling
│   ├── ForkStarted         -- parallel branch coordination
│   ├── RunWorkflowStarted  -- child workflow tracking
│   └── TaskRetryScheduled  -- retry delay scheduling
├── ActivityStarted (execute and continue)
│   ├── EmitStarted         -- publish CloudEvent
│   ├── CallHttpStarted     -- HTTP request
│   ├── RunScriptStarted    -- script execution
│   └── RunShellStarted     -- shell command
└── TaskScheduled (navigation)
```

## Config Objects

Each `ActivityStarted` event carries a config object with all data needed for execution:

```kotlin
// Emit
@Serializable
data class EmitConfig(
    val event: CloudEventData  // source, type, data, etc.
)

// HTTP Call
@Serializable
data class CallHttpConfig(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: JsonElement?,
    val output: HTTPOutput,
    val redirect: Boolean,
    val authentication: AuthenticationData?
)

// Script
@Serializable
data class RunScriptConfig(
    val language: String,
    val code: String,
    val arguments: Map<String, String>?,
    val environment: Map<String, String>?,
    val await: Boolean,
    val returnType: ProcessReturnType
)

// Shell
@Serializable
data class RunShellConfig(
    val command: String,
    val arguments: Map<String, String>?,
    val environment: Map<String, String>?,
    val await: Boolean,
    val returnType: ProcessReturnType
)
```

## ActivityStarted Base

```kotlin
@Serializable
sealed class ActivityStarted : WorkflowEvent() {
    abstract val rawOutput: JsonElement  // pass-through for resume

    fun resumeCompleted(output: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
        nodeStack = nodeStack,
        rawOutput = output,
    )

    fun resumeFailed(error: InternalException.Error) = WorkflowCommand.ResumeWithFailedTask(
        nodeStack = nodeStack,
        error = error,
    )
}
```

## Implementation Steps

### Step 1: Define ActivityStarted hierarchy in WorkflowState.kt

- [ ] Create `sealed class ActivityStarted : WorkflowEvent()`
- [ ] Add `resumeCompleted()` and `resumeFailed()` methods
- [ ] Move `EmitStarted` to extend `ActivityStarted` (update serialization)
- [ ] Create `CallHttpStarted` with `CallHttpConfig`
- [ ] Create `RunScriptStarted` with `RunScriptConfig`
- [ ] Create `RunShellStarted` with `RunShellConfig`

### Step 2: Create config data classes

- [ ] Create `EmitConfig` (extract from current CloudEvent building)
- [ ] Create `CallHttpConfig` (extract from CallHttpProcessor)
- [ ] Create `RunScriptConfig` (extract from RunScriptProcessor)
- [ ] Create `RunShellConfig` (extract from RunShellProcessor)
- [ ] Ensure all configs are `@Serializable`

### Step 3: Update processors to return ActivityStarted

For each activity processor:
- [ ] Set `override val isAsync = true`
- [ ] Implement `startedEvent()` to return the appropriate `ActivityStarted`
- [ ] Move config building logic from `execute()` to `startedEvent()`
- [ ] Remove `execute()` override (or make it throw)

Processors to update:
- [ ] `EmitProcessor` - already async, update event type
- [ ] `CallHttpProcessor` - extract config, return `CallHttpStarted`
- [ ] `RunScriptProcessor` - extract config, return `RunScriptStarted`
- [ ] `RunShellProcessor` - extract config, return `RunShellStarted`

### Step 4: Create ActivityExecutor

```kotlin
interface ActivityExecutor {
    suspend fun execute(event: ActivityStarted): JsonElement
}

// Default implementation with real I/O
class DefaultActivityExecutor(
    private val httpClient: HttpClient,
    // ... other dependencies
) : ActivityExecutor {
    override suspend fun execute(event: ActivityStarted): JsonElement = when (event) {
        is CallHttpStarted -> executeHttp(event.config)
        is RunScriptStarted -> executeScript(event.config)
        is RunShellStarted -> executeShell(event.config)
        is EmitStarted -> { /* fire-and-forget, return rawOutput */ event.rawOutput }
    }
}

// Test implementation
class MockActivityExecutor(
    private val responses: Map<KClass<out ActivityStarted>, JsonElement>
) : ActivityExecutor {
    override suspend fun execute(event: ActivityStarted): JsonElement =
        responses[event::class] ?: event.rawOutput
}
```

### Step 5: Update FullOrchestrator

- [ ] Add `ActivityExecutor` parameter (with default implementation)
- [ ] Add handler for `ActivityStarted` in `resume()`:

```kotlin
is ActivityStarted -> {
    val output = try {
        activityExecutor.execute(serdeEvent)
    } catch (e: Exception) {
        return resume(workflow, serdeEvent.resumeFailed(Error.from(e)), serde)
    }
    resume(workflow, serdeEvent.resumeCompleted(output), serde)
}
```

### Step 6: Move execution logic to ActivityExecutor

- [ ] Move HTTP call logic from `CallHttpProcessor` to executor
- [ ] Move script execution logic from `RunScriptProcessor` to executor
- [ ] Move shell execution logic from `RunShellProcessor` to executor
- [ ] Keep `HttpCall`, `Script`, `Shell` helper classes, just call them from executor

### Step 7: Update tests

- [ ] Update `FullOrchestrator` tests to use mock executor where needed
- [ ] Add tests for `ActivityExecutor` implementations
- [ ] Verify existing workflow tests still pass

### Step 8: Update lemline-runner (separate PR?)

- [ ] Update `WorkflowCommandHandler` to handle new event types
- [ ] Decide: use same `ActivityExecutor` pattern or inline handling?

## Design Decisions

1. **Authentication handling**: Config carries **resolved** auth data. Processor resolves from `use` section when building config. Executor receives everything it needs - no workflow context required.

2. **Secret resolution**: Resolved at config-building time in processor. Executor is a pure function: `config → output`.

3. **Error handling**: Executor **throws exceptions**. Orchestrator wraps in try-catch and converts to `resumeFailed`. Consistent with existing codebase patterns.

4. **Emit in tests**: No-op is fine for unit tests. Mock executor returns `rawOutput`. Can optionally record invocations for verification.

## File Changes Summary

**New files:**
- `lemline-core/src/main/kotlin/com/lemline/core/activities/ActivityExecutor.kt`
- `lemline-core/src/main/kotlin/com/lemline/core/activities/configs/*.kt` (config classes)

**Modified files:**
- `WorkflowState.kt` - new event types
- `EmitProcessor.kt` - update event type
- `CallHttpProcessor.kt` - convert to async
- `RunScriptProcessor.kt` - convert to async
- `RunShellProcessor.kt` - convert to async
- `FullOrchestrator.kt` - add ActivityExecutor, handle events
- Various test files

## Success Criteria

1. All existing tests pass
2. `FullOrchestrator` can run workflows with mock activity executor
3. Processors no longer contain I/O code
4. New activity types can be added by:
   - Creating config class
   - Creating `*Started` event
   - Adding case to `ActivityExecutor`
