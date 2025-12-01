# Plan: Functional Activity Model with Module Separation

## Goal

Refactor lemline to:
1. Eliminate exception-driven control flow (`AsyncTaskException`)
2. Have activity processors return `*Started` events directly
3. Extract activity execution code into a new `lemline-activities` module
4. Keep `lemline-core` pure (orchestration only, no execution)

## Current State

**Exception-driven activities** (throw `AsyncTaskException`):
- `WaitProcessor` → throws `WaitStartedException`
- `RunWorkflowProcessor` → throws `RunWorkflowStartedException`
- `ForkProcessor` → throws `ForkStartedException`
- `EmitProcessor` → throws `EmitStartedException`

**Direct-execution activities**:
- `CallHttpProcessor` → executes HTTP call inline via `HttpCall.execute()`

**FullOrchestrator limitation**: Cannot handle `EmitStarted` because it has no messaging infrastructure access.

## Proposed Design

### Core Principle

- **Activity tasks** (Wait, Emit, RunWorkflow, Fork, CallHTTP, etc.) → return `*Started` events
- **Control flow tasks** (Set, Switch, Do, For, Try, Raise) → continue as-is (no events)

### Module Structure

```
lemline/
├── lemline-common/          # Shared utilities (unchanged)
├── lemline-core/            # Pure orchestration - NO execution code
│   ├── orchestrator/        # StepByStepOrchestrator only
│   ├── processors/          # Signal *Started events for activities
│   └── activities/          # ActivityStarted sealed class, ActivityMarker
├── lemline-activities/      # NEW: All activity execution code
│   ├── WaitActivity.kt      # delay() implementation
│   ├── EmitActivity.kt      # CloudEvent publishing
│   ├── HttpCallActivity.kt  # HTTP client implementation (moved from core)
│   ├── ForkActivity.kt      # Parallel branch execution
│   └── RunWorkflowActivity.kt # Child workflow execution
├── lemline-full/            # NEW: FullOrchestrator + in-memory runtime
│   └── FullOrchestrator.kt  # Uses lemline-activities for execution
├── lemline-runner/          # Uses lemline-activities for production
└── lemline-docs/            # Documentation (unchanged)
```

**Dependency graph:**
```
lemline-common ◄── lemline-core ◄── lemline-activities ◄── lemline-full
                                                       ◄── lemline-runner
```

### Activity Model

Each activity in `lemline-activities` is a simple function/object:

```kotlin
// lemline-activities/src/main/kotlin/com/lemline/activities/WaitActivity.kt
object WaitActivity {
    suspend fun execute(event: WorkflowEvent.WaitStarted): JsonElement {
        val delayDuration = event.waitUntil - Clock.System.now()
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        return JsonNull  // Wait produces no output
    }
}

// lemline-activities/src/main/kotlin/com/lemline/activities/HttpCallActivity.kt
object HttpCallActivity {
    suspend fun execute(event: WorkflowEvent.HttpCallStarted): JsonElement {
        val httpCall = HttpCall(...)
        return httpCall.execute(event.request)
    }
}
```

### Processor Changes

Activity processors return `*Started` events instead of throwing exceptions:

```kotlin
// Before (WaitProcessor)
override suspend fun execute(...): JsonElement {
    throw WaitStartedException(state, transformedInput, Config(waitUntil = ...))
}

// After (WaitProcessor)
override suspend fun execute(...): JsonElement {
    // This will now return a marker that resumeFromTask interprets
    // as "this is an activity that needs execution"
    return activityStarted(WaitStarted(waitUntil = calculateWaitUntil()))
}
```

The key insight: `resumeFromTask` already returns `WorkflowEvent` which includes both:
- `TaskScheduled` (continue to next task)
- `*Started` events (activity needs execution)
- `WorkflowCompleted/Failed` (terminal states)

So the change is primarily:
1. Activity processors signal "started" via return value, not exception
2. Execution code moves to `lemline-activities`
3. Consumers (FullOrchestrator, lemline-runner) call activities directly

### Consumer Pattern

**FullOrchestrator** (or new lemline-full module):
```kotlin
suspend fun resume(workflow: Workflow, command: WorkflowCommand): WorkflowEvent.Outcome {
    val event = StepByStepOrchestrator.runByTask(workflow, command)

    return when (event) {
        is WorkflowEvent.WaitStarted -> {
            WaitActivity.execute(event)  // Uses lemline-activities
            resume(workflow, event.resume())
        }
        is WorkflowEvent.HttpCallStarted -> {
            val output = HttpCallActivity.execute(event)
            resume(workflow, event.resumeWith(output))
        }
        is WorkflowEvent.EmitStarted -> {
            EmitActivity.execute(event, cloudEventPublisher)  // Can now handle!
            resume(workflow, event.resume())
        }
        // ... other activities
        is WorkflowEvent.TaskScheduled -> resume(workflow, event.resume())
        is WorkflowEvent.Outcome -> event
    }
}
```

**lemline-runner** continues to use outbox pattern but calls activities for immediate execution.

## Implementation Steps

### Phase 1: Create ActivityStarted sealed class and marker
1. Create `ActivityStarted` sealed class in `lemline-core/activities/`
2. Create `ActivityMarker` and `ActivityStartedException` for signaling
3. Add helper function `activityStarted()` for processors

### Phase 2: Create lemline-activities module
4. Create new Gradle module `lemline-activities`
5. Move `HttpCall.kt` from lemline-core to lemline-activities
6. Create `WaitActivity`, `EmitActivity`, `HttpCallActivity`, `ForkActivity`, `RunWorkflowActivity`

### Phase 3: Add new WorkflowEvent types
7. Add `HttpCallStarted` to `WorkflowEvent` sealed class
8. Ensure all `*Started` events can be created from `ActivityStarted` types

### Phase 4: Migrate processors (one by one)
9. Migrate `WaitProcessor` - use `activityStarted()` instead of throwing
10. Migrate `EmitProcessor` - use `activityStarted()` instead of throwing
11. Migrate `RunWorkflowProcessor` - use `activityStarted()` instead of throwing
12. Migrate `ForkProcessor` - use `activityStarted()` instead of throwing
13. Migrate `CallHttpProcessor` - use `activityStarted()` instead of executing inline

### Phase 5: Create lemline-full module
14. Create new Gradle module `lemline-full`
15. Move `FullOrchestrator.kt` from lemline-core to lemline-full
16. Update `FullOrchestrator` to use `lemline-activities` for execution
17. Update tests to use new module

### Phase 6: Update StepByStepOrchestrator
18. Update `resumeFromTask()` to catch `ActivityStartedException` and convert to `WorkflowEvent.*Started`
19. Remove old `AsyncTaskException` handling

### Phase 7: Cleanup
20. Delete `AsyncTaskException` class
21. Update remaining tests
22. Update documentation and CLAUDE.md

## Files to Create

**New in lemline-core:**
- `src/main/kotlin/com/lemline/core/activities/ActivityStarted.kt` - sealed class
- `src/main/kotlin/com/lemline/core/activities/ActivityMarker.kt` - marker and helper

**New module `lemline-activities`:**
- `build.gradle.kts`
- `src/main/kotlin/com/lemline/activities/WaitActivity.kt`
- `src/main/kotlin/com/lemline/activities/EmitActivity.kt`
- `src/main/kotlin/com/lemline/activities/HttpCallActivity.kt`
- `src/main/kotlin/com/lemline/activities/ForkActivity.kt`
- `src/main/kotlin/com/lemline/activities/RunWorkflowActivity.kt`

**New module `lemline-full`:**
- `build.gradle.kts`
- `src/main/kotlin/com/lemline/full/FullOrchestrator.kt` (moved from core)
- `src/test/kotlin/...` (tests moved from core)

## Files to Modify

**lemline-core:**
- `orchestrator/WorkflowState.kt` - add `HttpCallStarted` event
- `orchestrator/StepByStepOrchestrator.kt` - handle `ActivityStartedException`
- `processors/WaitProcessor.kt` - use `activityStarted()`
- `processors/EmitProcessor.kt` - use `activityStarted()`
- `processors/RunWorkflowProcessor.kt` - use `activityStarted()`
- `processors/ForkProcessor.kt` - use `activityStarted()`
- `processors/CallHttpProcessor.kt` - use `activityStarted()` instead of inline execution

**Root:**
- `settings.gradle.kts` - add new modules
- `CLAUDE.md` - update architecture documentation

## Files to Move

- `lemline-core/.../tasks/calls/HttpCall.kt` → `lemline-activities`
- `lemline-core/.../orchestrator/FullOrchestrator.kt` → `lemline-full`
- Related tests for FullOrchestrator → `lemline-full`

## Files to Delete

- `lemline-core/src/main/kotlin/com/lemline/core/errors/AsyncTaskException.kt`

## Benefits

1. **No exception-driven control flow** - cleaner, more predictable
2. **Pure orchestration in lemline-core** - no execution dependencies
3. **Testability** - mock activities easily, test orchestration in isolation
4. **FullOrchestrator can handle Emit** - uses lemline-activities with injected publisher
5. **Clear module boundaries** - core = orchestration, activities = execution
6. **Extensibility** - easy to add new activity types

## Design Decisions

1. **FullOrchestrator** → Move to new module `lemline-full`
   - Keeps `lemline-core` pure (no execution dependencies)
   - Clean module boundaries: core = orchestration, activities = execution, full = in-memory runtime

2. **Signal method** → Marker return
   - Activity processors return a special marker object (e.g., `ActivityMarker`)
   - Orchestrator checks for marker and extracts the `*Started` event
   - Example: `return ActivityMarker(WaitStarted(waitUntil = ...))`

### Marker Design

```kotlin
// lemline-core/src/main/kotlin/com/lemline/core/activities/ActivityMarker.kt

/**
 * Marker returned by activity processors to signal that an activity has started
 * and needs execution by the consumer (FullOrchestrator or runner).
 */
data class ActivityMarker<T : ActivityStarted>(
    val activity: T
)

// Helper function for processors
inline fun <reified T : ActivityStarted> activityStarted(activity: T): Nothing {
    // This could throw a special marker exception that resumeFromTask catches
    // OR we change the return type to allow ActivityMarker
    throw ActivityStartedException(activity)
}

// Sealed class for all activity started events
sealed class ActivityStarted {
    data class Wait(val waitUntil: Instant) : ActivityStarted()
    data class Emit(val cloudEvent: CloudEvent) : ActivityStarted()
    data class HttpCall(val request: HttpCallRequest) : ActivityStarted()
    data class RunWorkflow(val config: RunWorkflowConfig) : ActivityStarted()
    data class Fork(val branches: List<Node<*>>, val input: JsonElement) : ActivityStarted()
}
```

**Note**: The marker approach still uses an exception under the hood (to break out of the execution flow), but it's a cleaner, typed marker rather than multiple exception subclasses.
