# Node Processors

## Overview

Each task type has a `NodeProcessor` implementing its execution logic. Processors are stateless; they receive state and return updated state.

## Key Files

| File | Purpose |
|------|---------|
| `processors/NodeProcessor.kt` | Base interface |
| `processors/DoProcessor.kt` | Sequential execution |
| `processors/ForProcessor.kt` | Iteration |
| `processors/TryProcessor.kt` | Error handling |
| `processors/ForkProcessor.kt` | Parallel branches |
| `processors/SwitchProcessor.kt` | Conditional branching |

---

## NodeProcessor Interface

```kotlin
interface NodeProcessor<T : TaskBase, S : TaskState> {
    val node: Node<T>
    fun createInitialState(): S
    fun getNextStepInfo(
        state: S,
        dataset: JsonElement,      // Transformed input
        scope: Scope,              // Expression variables
        direction: Direction       // How we entered
    ): NextStepInfo<S>
}
```

### NextStepInfo

```kotlin
data class NextStepInfo<S : TaskState>(
    val state: S,                                    // Updated state
    val rawOutput: JsonElement,                      // Output before transformation
    val stateUpdates: Map<NodePosition, TaskState?>, // null = remove
    val flowDirective: FlowDirective,                // Navigation
    val newContext: JsonObject? = null               // Exports
)
```

### FlowDirective

```kotlin
sealed class FlowDirective {
    object Continue           // Next sibling or parent
    object End                // End workflow
    data class Then(val target: String)  // Go to named task
}
```

### Direction

```kotlin
enum class Direction {
    FROM_PARENT,   // First entry
    FROM_CHILD,    // Returning from child
    FROM_SIBLING,  // From previous sibling
    SKIPPING       // Via "then" directive
}
```

---

## Control Flow Processors

### DoProcessor

```kotlin
class DoProcessor(node: Node<DoTask>) : NodeProcessor<DoTask, DoState> {
    override fun getNextStepInfo(state, dataset, scope, direction) = when (direction) {
        FROM_PARENT, FROM_SIBLING -> {
            // Start first child
            NextStepInfo(state = DoState(index = 0), stateUpdates = ..., flowDirective = Continue)
        }
        FROM_CHILD -> {
            val nextIndex = state.index + 1
            if (nextIndex < node.children!!.size) {
                NextStepInfo(state = DoState(index = nextIndex), ...)
            } else {
                NextStepInfo(stateUpdates = mapOf(node.position to null), ...)  // Done
            }
        }
    }
}
```

### ForProcessor

```kotlin
class ForProcessor(node: Node<ForTask>) : NodeProcessor<ForTask, ForState> {
    override fun getNextStepInfo(state, dataset, scope, direction) = when (direction) {
        FROM_PARENT -> {
            val collection = evalList(dataset, node.task.`in`, scope)
            NextStepInfo(state = ForState(collection.drop(1), index = 0), rawOutput = collection.first(), ...)
        }
        FROM_CHILD -> {
            if (state.collection.isNotEmpty() && shouldContinue) {
                NextStepInfo(state = ForState(collection.drop(1), index + 1), rawOutput = next, ...)
            } else {
                NextStepInfo(stateUpdates = mapOf(node.position to null), ...)
            }
        }
    }
}
```

### SwitchProcessor

```kotlin
class SwitchProcessor(node: Node<SwitchTask>) : NodeProcessor<SwitchTask, SwitchState> {
    override fun getNextStepInfo(state, dataset, scope, direction): NextStepInfo<SwitchState> {
        for (case in node.task.cases) {
            if (case.`when` == null || evalBoolean(dataset, case.`when`, scope)) {
                return NextStepInfo(flowDirective = case.then?.toFlowDirective() ?: Continue, ...)
            }
        }
        return NextStepInfo(flowDirective = Continue, ...)
    }
}
```

---

## Activity Processors

Activities may throw `AsyncTaskException` to pause execution.

### WaitProcessor

```kotlin
class WaitProcessor(node: Node<WaitTask>) : NodeProcessor<WaitTask, WaitState> {
    override fun getNextStepInfo(state, dataset, scope, direction): NextStepInfo<WaitState> {
        throw WaitStartedException(state = state, config = Config(waitUntil = calculateWaitUntil()))
    }
}
```

### RunProcessor (Child Workflow)

```kotlin
class RunProcessor(node: Node<RunTask>) : NodeProcessor<RunTask, RunState> {
    override fun getNextStepInfo(state, dataset, scope, direction) = when (node.task.run) {
        is RunWorkflow -> throw RunWorkflowStartedException(state, config = ...)
        is RunShell, is RunScript -> NextStepInfo(rawOutput = executeSync(), ...)
    }
}
```

---

## Creating a New Processor

### 1. Define State

```kotlin
@Serializable
data class CustomState(
    override val startedAt: Instant = Clock.System.now(),
    val customField: String = ""
) : TaskState()
```

### 2. Implement Processor

```kotlin
class CustomProcessor(override val node: Node<CustomTask>) : NodeProcessor<CustomTask, CustomState> {
    override fun createInitialState() = CustomState()
    override fun getNextStepInfo(state, dataset, scope, direction): NextStepInfo<CustomState> {
        val result = process(node.task, dataset)
        return NextStepInfo(state, rawOutput = result, stateUpdates = mapOf(node.position to null), flowDirective = Continue)
    }
}
```

### 3. Register

```kotlin
fun createProcessor(node: Node<*>): NodeProcessor<*, *> = when (node.task) {
    is DoTask -> DoProcessor(node as Node<DoTask>)
    is CustomTask -> CustomProcessor(node as Node<CustomTask>)
    else -> throw IllegalArgumentException("Unknown: ${node.task::class}")
}
```

---

## Expression Helpers

```kotlin
fun evalBoolean(data: JsonElement, expr: String, scope: Scope): Boolean
fun evalList(data: JsonElement, expr: String, scope: Scope): List<JsonElement>
fun eval(data: JsonElement, expr: JsonElement, scope: Scope): JsonElement
```

---

## Testing

```kotlin
@Test
fun `DoProcessor iterates children`() {
    val node = createDoNode(childCount = 3)
    val processor = DoProcessor(node)

    var result = processor.getNextStepInfo(processor.createInitialState(), JsonObject(), emptyScope, FROM_PARENT)
    assertEquals(0, (result.state as DoState).index)

    result = processor.getNextStepInfo(result.state, JsonObject(), emptyScope, FROM_CHILD)
    assertEquals(1, (result.state as DoState).index)
}
```
