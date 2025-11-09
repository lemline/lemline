# Simplified Workflow Execution Implementation Plan

**Date Started**: 2025-01-08
**Date Updated**: 2025-11-09
**Status**: Phase 1A Complete ✅ | Phase 1B Complete ✅ | Phase 1C In Progress

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

### Phase 1A: Core Foundation ✅ COMPLETE

**Date Completed**: 2025-01-08
**Goal**: Get basic sequential workflow running

1. **Fix Compilation** ✅
   - Removed TryTaskState
   - Removed ErrorHandler
   - Removed duplicate FlowDirective (using SDK type)
   - Renamed `continue` → `continueNavigation`
   - Added @OptIn annotations
   - Using SDK types throughout

2. **Core Infrastructure** ✅
   - NodeState base class with immutable/mutable separation
   - DoTaskState, ForTaskState, SwitchTaskState, ActivityTaskState
   - ExecutionOrchestrator with functional execution loop
   - NodeInstance base class with scope support
   - StepResult model with SDK FlowDirective

**Files Created** (~1,437 lines):
- `models/StepResult.kt`
- `state/NodeState.kt`, `DoTaskState.kt`, `ForTaskState.kt`, `SwitchTaskState.kt`, `ActivityTaskState.kt`
- `ExecutionOrchestrator.kt`
- `nodes/NodeInstance.kt`

### Phase 1B: Node Implementations ✅ COMPLETE

**Date Completed**: 2025-11-09
**Goal**: Implement DoTask and SetTask with comprehensive tests

1. **Implement DoNodeInstance** ✅
   - Sequential execution with state management
   - Child navigation (enter → execute → re-enter loop)
   - Goto flow directive support via childNames map
   - RootTask handling (converted to DoTask internally)

2. **Implement SetNodeInstance** ✅
   - JQ expression evaluation with scope
   - Data merging into dataset
   - ActivityTaskState integration

3. **Implement buildNodeInstance() Factory** ✅
   - Creates appropriate NodeInstance subclasses
   - Supports DoTask, SetTask, RootTask
   - Extensible for future task types

4. **Comprehensive Tests** ✅
   - Simple set task workflow
   - Sequential set tasks (DoTask with multiple children)
   - Expression evaluation (JQ with $input references)
   - All 3 tests passing

**Files Created** (~381 lines):
- `nodes/DoNodeInstance.kt` (141 lines)
- `nodes/SetNodeInstance.kt` (87 lines)
- `test/ExecutionOrchestratorTest.kt` (155 lines)

### Phase 1C: Iteration (Next Priority)

1. **Implement ForNodeInstance**
   - Iteration with scope variables (item, index)
   - While condition support
   - ForTaskState integration

2. **Test ForTask workflows**
   - Simple iteration over array
   - Iteration with while condition
   - Nested iteration

### Phase 1D: Branching

1. **Implement SwitchNodeInstance**
   - Conditional branching with case evaluation
   - Default case handling
   - SwitchTaskState integration

2. **Test Switch workflows**
   - Basic switch with literal cases
   - Switch with expression conditions
   - Default case handling

### Phase 1E: Activities

1. **Implement CallHttpNodeInstance**
   - HTTP calls with authentication
   - Request/response transformation
   - ActivityTaskState integration

2. **Implement EmitNodeInstance**
   - Event emission

3. **Implement WaitNodeInstance**
   - Time-based delays

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
✅ DoNodeInstance.kt (completed - 141 lines)
✅ SetNodeInstance.kt (completed - 87 lines)
✅ ExecutionOrchestratorTest.kt (completed - 155 lines)
⏳ ForNodeInstance.kt (next priority)
⏳ SwitchNodeInstance.kt
⏳ CallHttpNodeInstance.kt
⏳ EmitNodeInstance.kt
⏳ WaitNodeInstance.kt

Note: buildNodeInstance() factory function is implemented in DoNodeInstance.kt
Note: RootTask handling is built into buildNodeInstance() factory
```

---

## Success Criteria

### Phase 1A ✅ COMPLETE

✅ Code compiles without errors
✅ State classes with immutable/mutable separation
✅ ExecutionOrchestrator with functional execution loop
✅ NodeInstance base class with scope support
✅ SDK types integrated throughout

### Phase 1B ✅ COMPLETE

✅ Can execute simple Do workflow with Set tasks
✅ Sequential execution verified (multiple tasks in sequence)
✅ Dataset flows correctly through nodes
✅ Scope is built correctly for expression evaluation
✅ JQ expression evaluation working
✅ All unit tests passing (3/3)

### Phase 1C (In Progress)

⏳ ForTask iteration with scope variables
⏳ While condition support
⏳ Nested iteration support

---

## Next Steps

### Immediate (Phase 1C)
1. **Implement ForNodeInstance** - Iteration with item/index scope variables
2. **Test ForTask workflows** - Simple iteration, while conditions, nested loops

### Near Term (Phase 1D-E)
3. **Implement SwitchNodeInstance** - Conditional branching
4. **Implement CallHttpNodeInstance** - HTTP activity tasks
5. **Implement EmitNodeInstance** - Event emission
6. **Implement WaitNodeInstance** - Time-based delays

### Future (Phase 2)
7. **Error Handling** - TryTask, catch blocks, retry logic
8. **Advanced Features** - ForkTask (parallel), ListenTask (events), RunTask (child workflows)
9. **Production Integration** - StepByStepRunner, serialization, persistence

---

## Progress Timeline

- **2025-01-08**: Phase 1A Complete - Core foundation (~1,437 lines)
- **2025-11-09**: Phase 1B Complete - DoTask/SetTask implementations (~381 lines)
- **Next**: Phase 1C - ForTask iteration
- **Future**: Phase 1D - SwitchTask branching
- **Future**: Phase 1E - Activity tasks (CallHttp, Emit, Wait)

**Total Completed**: ~1,818 lines of tested, working code
