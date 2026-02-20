# Functional Processors Refactoring Plan

## Goal

Refactor `NodeProcessor` implementations from class instances to stateless singleton objects, consistent with the
functional approach used in `StepByStepOrchestrator`.

## Current State

```
NodeProcessor<T, S> (abstract class)
├── Constructor: node: Node<T>
├── Shared orchestration logic (~500 lines)
│   ├── enterFromParent() / enterFromChild()
│   ├── completeTask() / continueTo() / continueToParent()
│   ├── Input/output transformation (transformInput, transformOutput)
│   ├── Schema validation (validateInput, validateOutput)
│   ├── Expression evaluation (eval, evalBoolean, evalList)
│   └── Error handling (raiseError)
├── Task-specific abstract methods
│   ├── stateEnterFromParent()
│   ├── stateEnterFromChild()
│   ├── getNextNode()
│   ├── execute()
│   └── startedEvent()
└── Lazy properties: use, logger
```

**15 Processor Implementations:**

- Control flow: `RootProcessor`, `DoProcessor`, `ForProcessor`, `SwitchProcessor`, `TryProcessor`, `ForkProcessor`
- Activities: `SetProcessor`, `RaiseProcessor`, `CallHttpProcessor`, `WaitProcessor`, `EmitProcessor`, `ListenProcessor`
- Run variants: `RunShellProcessor`, `RunScriptProcessor`, `RunWorkflowProcessor`

**Special Cases:**

- `TryProcessor.isCatching()` and `handleError()` called directly from `StepByStepOrchestrator`
- `ForkProcessor` and `ListenProcessor` return async events handled by orchestrators
- `ForProcessor` uses `evalList()` for collection evaluation

---

## Target State

```
NodeProcessor<T, S> (interface)
├── Task-specific methods (receive node as parameter)
│   ├── stateEnterFromParent(node, transformedInput, scope): S
│   ├── stateEnterFromChild(node, state, output, scope, nodeName): S
│   ├── getNextNode(node, state, dataset, scope): NavigationInfo
│   ├── execute(node, transformedInput, scope, state): JsonElement
│   ├── startedEvent(node, nodeStack, transformedInput, scope): WorkflowEvent
│   └── isAsync: Boolean
└── Default implementations where applicable

NodeProcessorOps (object)
├── Shared orchestration logic
│   ├── enterFromParent(processor, node, nodeStack, rawInput, ...)
│   ├── enterFromChild(processor, node, nodeStack, output, ...)
│   └── completeTask(processor, node, nodeStack, rawOutput, ...)
└── Helper methods
    ├── transformInput(node, rawInput, scope)
    ├── transformOutput(node, rawOutput, scope)
    ├── validateInput/Output(node, data)
    ├── eval/evalBoolean/evalList(node, data, expr, scope)
    └── raiseError(node, type, title, details)

NodeProcessors (object) - already exists
├── createProcessor(node): NodeProcessor<*, *>  [DONE]
└── getUse(node): Use?  [NEW - replaces lazy `use` property]

Individual processors as objects:
├── object DoProcessor : NodeProcessor<DoTask, DoState>
├── object ForProcessor : NodeProcessor<ForTask, ForState>
├── object TryProcessor : NodeProcessor<TryTask, TryState>
│   ├── fun isCatching(node, error, state, scope): Boolean
│   └── fun handleError(node, failingNode, error, state, nodeStack): WorkflowEvent
└── ... (12 more)
```

---

## Phase 1: Prepare Infrastructure

### 1.1 Create `NodeProcessorOps` with helper methods

Extract stateless helper methods from `NodeProcessor` base class:

```kotlin
// New file: NodeProcessorOps.kt
object NodeProcessorOps {
    private val logger = logger()

    // Expression evaluation
    fun eval(node: Node<*>, data: JsonElement, expr: JsonElement, scope: Scope, force: Boolean = false): JsonElement
    fun evalBoolean(node: Node<*>, data: JsonElement, expr: String, name: String, scope: Scope): Boolean
    fun evalList(node: Node<*>, data: JsonElement, expr: String, name: String, scope: Scope): List<JsonElement>

    // Input/Output transformation
    fun transformInput(node: Node<*>, rawInput: JsonElement, scope: Scope): JsonElement
    fun transformOutput(node: Node<*>, rawOutput: JsonElement, scope: Scope): JsonElement

    // Validation
    fun validateInput(node: Node<*>, rawInput: JsonElement)
    fun validateOutput(node: Node<*>, transformedOutput: JsonElement)
    fun validate(node: Node<*>, data: JsonElement, schemaUnion: SchemaUnion)

    // Context export
    fun exportToContext(node: Node<*>, transformedOutput: JsonElement, scope: Scope): JsonObject?

    // Condition check
    fun checkIf(node: Node<*>, rawInput: JsonElement, scope: Scope): Boolean

    // Flow directive
    fun getFlowDirective(node: Node<*>): FlowDirective?

    // Error handling
    fun raiseError(
        node: Node<*>,
        type: WorkflowErrorType,
        title: String?,
        details: String? = null,
        status: Int? = null
    ): Nothing

    // RunWorkflow input transformation
    fun runWorkflowInput(node: Node<*>, data: JsonElement, subFlowInput: SubflowInput?, scope: Scope): JsonElement
}
```

### 1.2 Add `getUse()` to `NodeProcessors`

Replace the lazy `use` property:

```kotlin
object NodeProcessors {
    // Existing
    fun <T : TaskBase> createProcessor(node: Node<T>): NodeProcessor<T, NodeState>

    // New - replaces lazy `use` property
    fun getUse(node: Node<*>): Use? {
        var rootNode: Node<*> = node
        while (rootNode.parent != null) rootNode = rootNode.parent
        if (rootNode.task !is RootTask) throw IllegalStateException("RootNode has no RootTask!")
        return rootNode.task.use
    }
}
```

### 1.3 Tests

- [ ] Verify all existing tests pass (no behavior changes yet)

---

## Phase 2: Create `NodeProcessor` Interface

### 2.1 Define the interface

```kotlin
// New structure for NodeProcessor.kt
interface NodeProcessor<T : TaskBase, S : NodeState> {

    // Required: Create initial state when entering node
    fun stateEnterFromParent(node: Node<T>, transformedInput: JsonElement, scope: Scope): S

    // Optional: Update state when re-entering from child (default: return state unchanged)
    fun stateEnterFromChild(node: Node<T>, state: S, output: JsonElement, scope: Scope, nodeName: String?): S = state

    // Required: Determine next node to navigate to
    fun getNextNode(node: Node<T>, state: S, dataset: JsonElement, scope: Scope): NavigationInfo

    // Optional: Execute node action (default: pass through input)
    suspend fun execute(node: Node<T>, transformedInput: JsonElement, scope: Scope, state: S): JsonElement =
        transformedInput

    // Async behavior
    val isAsync: Boolean get() = false

    // Optional: Return started event for async activities
    fun startedEvent(node: Node<T>, nodeStack: NodeStack, transformedInput: JsonElement, scope: Scope): WorkflowEvent =
        error("startedEvent should not be called for ${node.task::class.simpleName}")
}
```

### 2.2 Move orchestration to `NodeProcessorOps`

Move these methods from base class to `NodeProcessorOps`:

```kotlin
object NodeProcessorOps {
    // ... helpers from Phase 1 ...

    // Main entry points (called by orchestrators)
    suspend fun <T : TaskBase, S : NodeState> enterFromParent(
        processor: NodeProcessor<T, S>,
        node: Node<T>,
        nodeStack: NodeStack,
        rawInput: JsonElement,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent

    suspend fun <T : TaskBase, S : NodeState> enterFromChild(
        processor: NodeProcessor<T, S>,
        node: Node<T>,
        nodeStack: NodeStack,
        output: JsonElement,
        flowDirective: FlowDirective?,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent

    suspend fun <T : TaskBase, S : NodeState> completeTask(
        processor: NodeProcessor<T, S>,
        node: Node<T>,
        rawOutput: JsonElement,
        currentFlowDirective: FlowDirective?,
        currentScope: Scope,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent

    // Internal navigation helpers
    private suspend fun <T : TaskBase, S : NodeState> continueTo(...)
    private fun <T : TaskBase, S : NodeState> continueToChild(...)
    private suspend fun <T : TaskBase, S : NodeState> continueToParent(...)
    private suspend fun <T : TaskBase, S : NodeState> continueToEnd(...)
    private fun getNextEvent(...)
    private fun forkBranchCompleted(...)
    private fun listenForEachCompleted(...)
}
```

---

## Phase 3: Convert Processors to Objects

Convert each processor class to an object. Order by complexity (simplest first):

### 3.1 Leaf processors (no children, simple logic)

1. **SetProcessor** - simplest, just evaluates expressions
2. **RaiseProcessor** - throws error
3. **WaitProcessor** - async wait
4. **EmitProcessor** - async emit

Example conversion:

```kotlin
// Before
class SetProcessor(node: Node<SetTask>) : NodeProcessor<SetTask, SetState>(node) {
    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope) = SetState()

    override suspend fun execute(transformedInput: JsonElement, scope: Scope, state: SetState): JsonElement {
        return eval(transformedInput, LemlineJson.encodeToElement(getSet()), scope)
    }

    private fun getSet(): SetTaskConfiguration = node.task.set ?: throw NoSuchElementException("SetTask has no set")
}

// After
object SetProcessor : NodeProcessor<SetTask, SetState> {

    override fun stateEnterFromParent(node: Node<SetTask>, transformedInput: JsonElement, scope: Scope) = SetState()

    override suspend fun execute(
        node: Node<SetTask>,
        transformedInput: JsonElement,
        scope: Scope,
        state: SetState
    ): JsonElement {
        val setConfig = node.task.set ?: throw NoSuchElementException("SetTask has no set")
        return NodeProcessorOps.eval(node, transformedInput, LemlineJson.encodeToElement(setConfig), scope)
    }

    override fun getNextNode(node: Node<SetTask>, state: SetState, dataset: JsonElement, scope: Scope) =
        NavigationInfo(node.parent, NodeProcessorOps.getFlowDirective(node))
}
```

### 3.2 Control flow processors

5. **RootProcessor** - workflow root
6. **DoProcessor** - sequential execution
7. **SwitchProcessor** - conditional branching
8. **ForkProcessor** - parallel execution (async)
9. **ListenProcessor** - event listening (async)

### 3.3 Complex processors

10. **ForProcessor** - uses evalList, scope variables
11. **TryProcessor** - error handling, retry logic, special methods (isCatching, handleError)

### 3.4 Activity processors

12. **CallHttpProcessor** - HTTP calls (async)
13. **RunShellProcessor** - shell execution
14. **RunScriptProcessor** - script execution
15. **RunWorkflowProcessor** - child workflow (async)

---

## Phase 4: Update Call Sites

### 4.1 Update `NodeProcessors.createProcessor()`

Return interface type, dispatch to objects:

```kotlin
@Suppress("UNCHECKED_CAST")
fun <T : TaskBase> getProcessor(node: Node<T>): NodeProcessor<T, *> = when (node.task) {
        is DoTask -> DoProcessor
        is ForTask -> ForProcessor
        is SetTask -> SetProcessor
        // ...
    } as NodeProcessor<T, *>
```

### 4.2 Update `StepByStepOrchestrator`

```kotlin
// Before
node.processor.enterFromParent(nodeStack, rawInput, workflowInfo, lifecycleHook)

// After
val processor = NodeProcessors.getProcessor(node)
NodeProcessorOps.enterFromParent(processor, node, nodeStack, rawInput, workflowInfo, lifecycleHook)
```

### 4.3 Update `TryProcessor` special calls

```kotlin
// Before
val processor = current.processor as TryProcessor
if (processor.isCatching(exception.error, tryState, nodeStack.stateScope)) {
    processor.handleError(...)
}

// After
if (TryProcessor.isCatching(current as Node<TryTask>, exception.error, tryState, nodeStack.stateScope)) {
    TryProcessor.handleError(current, ...)
}
```

### 4.4 Update `Node.kt`

Remove `processor` property entirely. All processor access goes through `NodeProcessors`:

```kotlin
data class Node<T : TaskBase>(
    val position: NodePosition,
    val task: T,
    val name: String,
    val parent: Node<*>? = null
) {
    val definition: JsonObject by lazy { LemlineJson.encodeToElement(task) }
    val children: List<Node<*>>? by lazy { WorkflowParser.parseChildren(this) }
    // No more processor property
}
```

---

## Phase 5: Cleanup

### 5.1 Remove old code

- Delete abstract `NodeProcessor` class
- Remove processor imports from `Node.kt`
- Remove `processor` lazy property from `Node`

### 5.2 Final file structure

```
processors/
├── NodeProcessor.kt          # Interface definition
├── NodeProcessorOps.kt       # Shared orchestration logic
├── NodeProcessors.kt         # Factory/registry (already exists)
├── NavigationInfo.kt         # Data class (extract from NodeProcessor.kt)
├── DoProcessor.kt            # object DoProcessor
├── ForProcessor.kt           # object ForProcessor
├── TryProcessor.kt           # object TryProcessor + isCatching/handleError
├── ... (12 more processor objects)
```

### 5.3 Documentation updates

- Update `core-processors.md` with new patterns
- Update `AGENTS.md` if needed

---

## Testing Strategy

After each phase:

1. Run `./gradlew :lemline-core:test`
2. Run `./gradlew :lemline-runner:test` (integration tests)
3. Verify no regressions

---

## Risks and Mitigations

| Risk                               | Mitigation                                                        |
|------------------------------------|-------------------------------------------------------------------|
| Type safety with generics          | Use `@Suppress("UNCHECKED_CAST")` at boundaries (same as current) |
| Breaking orchestrator calls        | Update call sites in same commit as processor changes             |
| Missing edge cases in TryProcessor | Extra test coverage for retry/catch scenarios                     |
| Performance regression             | Unlikely - objects are more efficient than class instances        |

---

## Estimated Scope

| Phase   | Files Changed | New Files               | Complexity          |
|---------|---------------|-------------------------|---------------------|
| Phase 1 | 1-2           | 1 (NodeProcessorOps.kt) | Low                 |
| Phase 2 | 1             | 0                       | Medium              |
| Phase 3 | 15            | 0                       | Medium (repetitive) |
| Phase 4 | 3-4           | 0                       | Medium              |
| Phase 5 | 2-3           | 1 (NavigationInfo.kt)   | Low                 |

**Total:** ~25 files touched, 2 new files

---

## Decision Points

Before proceeding, confirm:

1. **Naming:** `NodeProcessorOps` vs `ProcessorOrchestration` vs `NodeProcessorRunner`?
2. **File organization:** Keep all processors in `processors/` or create `processors/impl/`?
3. **TryProcessor:** Keep special methods as top-level functions or companion object?
