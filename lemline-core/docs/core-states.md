# Task States and Workflow State

## Overview

`TaskState` tracks execution progress for individual nodes. `TaskStates` maps positions to states. `WorkflowCommand` and `WorkflowEvent` communicate between caller and orchestrator.

## Key Files

| File | Purpose |
|------|---------|
| `states/TaskState.kt` | Base state class |
| `states/TaskStates.kt` | State map operations |
| `orchestrator/WorkflowState.kt` | Commands and events |

---

## TaskState Base

```kotlin
@Serializable
sealed class TaskState {
    abstract val startedAt: Instant
    open val scope: Scope get() = JsonObject(mapOf())  // Expression variables
}
```

---

## State Classes

| State | Purpose | Key Properties | Scope Variables |
|-------|---------|----------------|-----------------|
| `RootState` | Workflow root | `workflowId`, `workflowInput`, `context` | `$workflow`, `$context` |
| `DoState` | Sequential | `index` (current child) | - |
| `ForState` | Iteration | `collection`, `index`, `forEach`, `forAt` | `$item`, `$index` |
| `TryState` | Error handling | `attemptIndex`, `runningCatch`, `errorAs` | `$error` (in catch) |
| `ForkState` | Parallel | `startedAt` | - |
| `SwitchState` | Conditional | `startedAt` | - |
| `WaitState`, `CallState`, `SetState`, `RaiseState`, `RunState` | Activities | `startedAt` | - |

### RootState

```kotlin
data class RootState(
    override val startedAt: Instant,
    val workflowId: WorkflowId,
    val workflowInput: JsonElement,
    val context: Scope = buildJsonObject {},  // Exported data
    val hasWaitingParent: Boolean = false
) : TaskState()
```

### ForState

```kotlin
data class ForState(
    override val startedAt: Instant,
    val collection: List<JsonElement>,
    val index: Int,
    val forEach: String = "item",
    val forAt: String = "index"
) : TaskState() {
    override val scope: Scope get() = buildJsonObject {
        put(forEach, collection.firstOrNull() ?: JsonNull)
        put(forAt, JsonPrimitive(index))
    }
}
```

### TryState

```kotlin
data class TryState(
    override val startedAt: Instant,
    val transformedInput: JsonElement = JsonNull,
    val attemptIndex: Int = 0,
    val runningCatch: Boolean = false,
    val errorAs: String = "error"
) : TaskState() {
    fun newAttemptState() = copy(attemptIndex = attemptIndex + 1)
    fun toCatchState(error: Error) = copy(runningCatch = true, capturedError = error)
}
```

---

## TaskStates Map

```kotlin
typealias TaskStates = Map<NodePosition, TaskState>

fun TaskStates.updateWith(
    stateUpdates: Map<NodePosition, TaskState?>,  // null = remove
    newContext: JsonObject? = null
): TaskStates
```

**Lifecycle**: Created on node entry → Updated during execution → Removed on node exit

---

## WorkflowCommand

Instructions to orchestrator:

```kotlin
sealed class WorkflowCommand : WorkflowState() {
    data class ResumeFromTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective? = null
    )

    data class ResumeWithCompletedTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawOutput: JsonElement
    )

    data class ResumeWithFailedTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val error: InternalException.Error
    )
}
```

---

## WorkflowEvent

Results from orchestrator:

| Event | When | Contains |
|-------|------|----------|
| `TaskScheduled` | Ready for activity | `transformedInput`, `node` |
| `WaitStarted` | Timer needed | `config.waitUntil` |
| `RetryScheduled` | Retry delay | `config.retryAt` |
| `RunWorkflowStarted` | Child workflow | `config` (namespace, name, version) |
| `ForkStarted` | Parallel branches | `branches`, `compete` |
| `BranchCompleted` | Fork branch done | `branchIndex`, `output` |
| `BranchFailed` | Fork branch error | `branchIndex`, `error` |
| `WorkflowCompleted` | Success | `output` |
| `WorkflowFailed` | Error | `error` |

---

## Serialization

All states serialize for persistence and messaging:

```kotlin
val json = Json.encodeToString(TaskStates.serializer(), taskStates)
val restored = Json.decodeFromString(TaskStates.serializer(), json)
```

---

## Creating Custom State

```kotlin
@Serializable
data class CustomState(
    override val startedAt: Instant = Clock.System.now(),
    val counter: Int = 0
) : TaskState() {
    override val scope: Scope get() = buildJsonObject {
        put("counter", JsonPrimitive(counter))
    }
    fun increment() = copy(counter = counter + 1)
}
```

---

## Common Patterns

```kotlin
// Get root state
fun getRootState(taskStates: TaskStates): RootState =
    taskStates[NodePosition.root] as RootState

// Build scope from state chain
fun buildFullScope(taskStates: TaskStates, position: NodePosition): Scope {
    var scope = JsonObject(mapOf())
    var current: NodePosition? = position
    while (current != null) {
        taskStates[current]?.let { scope = it.scope.merge(scope) }
        current = current.parent
    }
    return scope
}
```

---

## Testing

```kotlin
@Test
fun `ForState provides iteration variables`() {
    val state = ForState(
        startedAt = Clock.System.now(),
        collection = listOf(JsonPrimitive("a")),
        index = 0
    )
    assertEquals("a", state.scope["item"]?.jsonPrimitive?.content)
    assertEquals(0, state.scope["index"]?.jsonPrimitive?.int)
}
```
