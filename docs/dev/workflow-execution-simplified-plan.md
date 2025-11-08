# Simplified Workflow Execution Implementation Plan

**Date**: 2025-01-08
**Status**: Active

## Simplifications

1. **Use SDK Types** - Reuse `io.serverlessworkflow.api.types.*` instead of creating custom types
2. **Skip Error Management** - Defer TryTask, ErrorHandler, catch/retry logic to later phase
3. **Focus on Core Flow** - Implement Do, For, Switch, and basic activities first

---

## Architecture Overview

### Existing SDK Types (Reuse)

From `io.serverlessworkflow.api.types`:
- ✅ `FlowDirective` - Already has `.get()` returning String | FlowDirectiveEnum
- ✅ `FlowDirectiveEnum` - CONTINUE, EXIT, END
- ✅ `TaskBase` - Base for all tasks
- ✅ `DoTask`, `ForTask`, `SwitchTask`, `SetTask`, `CallHTTP`, etc.
- ✅ `InputFrom`, `OutputAs`, `ExportAs`
- ✅ `SchemaUnion`

### New Types (Core Only)

**StepResult** - Return type for orchestration functions:
```kotlin
data class StepResult(
    val next: NodeInstance<*>?,
    val dataset: JsonElement,
    val flowDirective: FlowDirective?  // SDK type!
)
```

**NodeState<M>** - State with immutable/mutable separation (as already implemented)

---

## Simplified State Classes

### Keep (Core Flow)

1. **`NodeState.kt`** ✅ - Base class
2. **`DoTaskState.kt`** ✅ - Sequential execution
3. **`ForTaskState.kt`** ✅ - Iteration
4. **`SwitchTaskState.kt`** ✅ - Conditional branching
5. **`ActivityTaskState.kt`** ✅ - Leaf nodes

### Remove (Error Handling - Deferred)

1. ~~`TryTaskState.kt`~~ - Move to Phase 2
2. ~~`WorkflowError` duplicate~~ - Not needed yet

---

## Simplified Orchestration

### ExecutionOrchestrator

**Keep Core Functions**:
```kotlin
object ExecutionOrchestrator {
    suspend fun execute(workflow: NodeInstance<*>, input: JsonElement): JsonElement

    suspend fun run(current: NodeInstance<*>, dataset: JsonElement, flowDirective: FlowDirective?): StepResult

    suspend fun enter(node: NodeInstance<*>, datasetFromParent: JsonElement): StepResult

    suspend fun reEnter(node: NodeInstance<*>, datasetFromChild: JsonElement, flowDirective: FlowDirective?): StepResult

    suspend fun continueNavigation(node: NodeInstance<*>, dataset: JsonElement, flowDirective: FlowDirective?): StepResult

    suspend fun exitToUp(node: NodeInstance<*>, datasetForExit: JsonElement): StepResult
}
```

**Simplifications**:
- Remove exception handling (no try/catch for now)
- Remove `ErrorHandler` completely
- Exceptions just propagate up and fail the workflow
- Rename `continue` to `continueNavigation` (avoid keyword conflict)

---

## Implementation Priority

### Phase 1A: Core Foundation (Week 1)

**Goal**: Get basic sequential workflow running

1. **Fix Compilation** ✅
   - Remove TryTaskState
   - Remove ErrorHandler
   - Remove duplicate FlowDirective
   - Rename `continue` → `continueNavigation`
   - Add @OptIn annotations
   - Use SDK types

2. **Implement DoTaskNodeInstance** ✅
   ```kotlin
   class DoTaskNodeInstance(
       node: Node<DoTask>,
       parent: NodeInstance<*>?
   ) : NodeInstance<DoTask>(node, parent) {
       override var state: DoTaskState = DoTaskState(
           doSize = node.children?.size ?: 0,
           childNames = buildChildNamesMap(node.children)
       )

       init {
           children = node.children?.map { buildNodeInstance(it, this) } ?: emptyList()
       }

       override suspend fun execute(input: JsonElement): JsonElement = input  // Flow task, no action
   }
   ```

3. **Implement SetTaskNodeInstance** ✅
   ```kotlin
   class SetTaskNodeInstance(
       node: Node<SetTask>,
       parent: NodeInstance<*>?
   ) : NodeInstance<SetTask>(node, parent) {
       override var state: ActivityTaskState = ActivityTaskState()

       init {
           children = emptyList()  // Leaf node
       }

       override suspend fun execute(input: JsonElement): JsonElement {
           // Evaluate set expressions and merge into input
           return evalSet(node.task.set, input, scope)
       }
   }
   ```

4. **Simple Test** ✅
   ```yaml
   do:
     - setStatus:
         set:
           status: "processed"
     - setResult:
         set:
           result: .status
   ```

### Phase 1B: Iteration (Week 2)

1. **Implement ForTaskNodeInstance**
2. **Test ForTask workflow**

### Phase 1C: Branching (Week 3)

1. **Implement SwitchTaskNodeInstance**
2. **Test Switch workflow**

### Phase 1D: Activities (Week 4)

1. **Implement CallHttpNodeInstance**
2. **Implement EmitTaskNodeInstance**
3. **Implement WaitTaskNodeInstance**

---

## Testing Strategy (Simplified)

### Unit Tests

```kotlin
@Test
fun `test DoTask sequential execution`() = runTest {
    val definition = parseWorkflow("""
        do:
          - task1: { set: { a: 1 } }
          - task2: { set: { b: 2 } }
    """)

    val root = buildNodeInstance(definition)
    val output = ExecutionOrchestrator.execute(root, JsonNull)

    output shouldBe jsonObject {
        "a" to 1
        "b" to 2
    }
}
```

### Integration Tests (Deferred)

- Wait until StepByStepRunner integration

---

## What's NOT Included (Phase 2+)

1. **Error Handling**
   - TryTask
   - Catch blocks
   - Retry logic
   - WorkflowError propagation

2. **Advanced Features**
   - ForkTask (parallel branches)
   - ListenTask (event listening)
   - RunTask (child workflows)

3. **Production Integration**
   - State serialization/hydration
   - StepByStepRunner integration
   - Message broker integration
   - Database persistence

---

## File Changes Required

### Delete

```
❌ lemline-core/src/main/kotlin/com/lemline/core/execution/ErrorHandler.kt
❌ lemline-core/src/main/kotlin/com/lemline/core/execution/models/FlowDirective.kt (use SDK)
❌ lemline-core/src/main/kotlin/com/lemline/core/execution/state/TryTaskState.kt (defer)
```

### Modify

```
📝 ExecutionOrchestrator.kt
   - Remove exception handling
   - Rename continue → continueNavigation
   - Use SDK FlowDirective
   - Simplify execute() loop

📝 StepResult.kt
   - Use SDK FlowDirective type

📝 DoTaskState.kt
📝 ForTaskState.kt
📝 SwitchTaskState.kt
📝 ActivityTaskState.kt
   - Add @OptIn annotations
   - No other changes needed

📝 NodeInstance.kt
   - Add @OptIn annotation
   - Use SDK FlowDirective in getFlowDirective()
```

### Create

```
✨ DoTaskNodeInstance.kt
✨ ForTaskNodeInstance.kt
✨ SwitchTaskNodeInstance.kt
✨ SetTaskNodeInstance.kt
✨ CallHttpNodeInstance.kt
✨ RootNodeInstance.kt (extend existing)
✨ NodeInstanceBuilder.kt (factory to build tree)
```

---

## Success Criteria (Phase 1A)

✅ Code compiles without errors
✅ Can execute simple Do workflow with Set tasks
✅ State is properly separated (immutable/mutable)
✅ Dataset flows correctly through nodes
✅ Scope is built correctly for expression evaluation
✅ Unit tests pass

---

## Next Steps

1. **Now**: Remove error handling code (TryTaskState, ErrorHandler)
2. **Now**: Fix compilation errors (continue keyword, @OptIn, SDK types)
3. **Next**: Implement DoTaskNodeInstance + SetTaskNodeInstance
4. **Next**: Write simple test for Do + Set workflow
5. **Later**: Implement For, Switch, CallHttp
6. **Much Later**: Error handling (Phase 2)

---

## Estimated Timeline

- **Week 1**: Core foundation compiling + Do/Set working
- **Week 2**: ForTask working
- **Week 3**: SwitchTask working
- **Week 4**: CallHttp + other activities working

**Total**: 4 weeks to basic functional execution (without error handling)
