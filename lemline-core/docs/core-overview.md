# Core Module Overview

## Purpose

The `lemline-core` module implements the Serverless Workflow DSL v1.0 specification. It provides a pure, stateless execution engine that can be embedded in any runtime.

## Package Structure

```
com.lemline.core/
├── definitions/     # Workflow parsing and caching
├── errors/          # Exception types (AsyncTaskException, InternalException)
├── expressions/     # JQ expression evaluation
├── json/            # JSON serialization utilities
├── nodes/           # Node tree (Node, NodePosition, Token)
├── orchestrator/    # Orchestrators, WorkflowCommand, WorkflowEvent
├── processors/      # NodeProcessor per task type
├── states/          # TaskState subclasses
├── tasks/           # Activity runners (HTTP, Shell, Script)
├── utils/           # Branching, timeouts, jitter utilities
├── workflows/       # Workflow descriptors
└── schemas/         # Schema validation
```

## Design Principles

1. **Stateless Execution**: Full workflow state serializes to `TaskStates` map
2. **Immutable Definitions**: Parsed once into node trees, cached by version
3. **Exception-Driven Control**: Async operations signal via exceptions
4. **Pure Functions**: Deterministic outputs for testability

## Key Files

| File | Purpose |
|------|---------|
| `definitions/DefinitionCache.kt` | Parse and cache workflows |
| `orchestrator/StepByStepOrchestrator.kt` | Step-by-step execution |
| `orchestrator/FullOrchestrator.kt` | Full synchronous execution |
| `nodes/Node.kt` | Immutable node structure |
| `processors/NodeProcessor.kt` | Base processor interface |

---

## DSL Parsing

### DefinitionCache

Central registry for workflow definitions. Location: `definitions/DefinitionCache.kt`

```kotlin
object DefinitionCache {
    // Parse without caching
    fun parse(definition: String): Workflow

    // Parse and store in cache
    fun parseAndPut(definition: String): Workflow

    // Retrieve from cache
    fun getWorkflow(namespace: WorkflowNamespace, name: WorkflowName, version: WorkflowVersion): Workflow?

    // Get node map for navigation
    fun getNodesMap(workflow: Workflow): Map<NodePosition, Node<*>>

    // Get root node
    fun getRootNode(workflow: Workflow): Node<RootTask>
}
```

### Parsing Flow

1. Try YAML parsing (using `yamlMapper`)
2. Fall back to JSON if YAML fails
3. Map to `Workflow` using Jackson
4. Validate with Serverless Workflow SDK
5. Build immutable node tree recursively
6. Cache by `WorkflowIndex(namespace, name, version)`

### WorkflowIndex

Cache key combining workflow identity:

```kotlin
class WorkflowIndex(
    val namespace: WorkflowNamespace,
    val name: WorkflowName,
    val version: WorkflowVersion
)
```

---

## Supported DSL Features

| Category | Tasks | Notes |
|----------|-------|-------|
| **Control Flow** | Do, For, Switch, Try/Catch, Fork, Raise | All implemented |
| **Call Activities** | HTTP, OpenAPI, gRPC, Function, AsyncAPI | All implemented |
| **Run Activities** | Shell, Script, Workflow | All implemented |
| **Other Activities** | Wait, Set, Emit, Listen | All implemented |
| **Data** | Input/Output transform, Export, Schema | All implemented |
| **Expressions** | JQ 1.6 with scoped variables | All implemented |

---

## Adding a New Task Type

### Step 1: Create Model (if custom)

If extending beyond SDK types, add model in `models/tasks/`:

```kotlin
@Serializable
data class CustomTask(
    val customProperty: String,
    // ... other properties
) : TaskBase()
```

### Step 2: Create State Class

Add state in `states/`:

```kotlin
@Serializable
data class CustomState(
    override val startedAt: Instant = Clock.System.now(),
    // Task-specific state
) : TaskState()
```

### Step 3: Create Processor

Add processor in `processors/`:

```kotlin
class CustomProcessor(node: Node<CustomTask>) : NodeProcessor<CustomTask, CustomState> {

    override fun createInitialState(): CustomState = CustomState()

    override fun getNextStepInfo(
        state: CustomState,
        dataset: JsonElement,
        scope: Scope,
        direction: Direction
    ): NextStepInfo<CustomState> {
        // Implement task logic
        return NextStepInfo(
            state = state,
            rawOutput = result,
            stateUpdates = mapOf(node.position to state),
            flowDirective = FlowDirective.Continue
        )
    }
}
```

### Step 4: Register in Factories

Update `Node.kt` to create children (if container task):

```kotlin
val children: List<Node<*>>? by lazy {
    when (task) {
        // ... existing cases
        is CustomTask -> task.parseChildren(position, this)
        else -> null
    }
}
```

Update processor factory to instantiate:

```kotlin
fun createProcessor(node: Node<*>): NodeProcessor<*, *> = when (node.task) {
    // ... existing cases
    is CustomTask -> CustomProcessor(node as Node<CustomTask>)
    else -> throw IllegalArgumentException("Unknown task type")
}
```

### Step 5: Add Tests

Create test file in `src/test/kotlin/com/lemline/core/`:

```kotlin
class CustomTaskTest {
    @Test
    fun `should execute custom task`() = runTest {
        val yaml = """
            document:
              name: test-custom
              version: "1.0"
            do:
              - customTask:
                  custom:
                    customProperty: "value"
        """.trimIndent()

        val result = executeWorkflow(yaml, JsonObject(mapOf()))
        assertEquals(expected, result)
    }
}
```

---

## Testing Patterns

### Unit Testing with FullOrchestrator

```kotlin
@Test
fun `should process workflow`() = runTest {
    val workflow = DefinitionCache.parse(yamlDefinition)
    val orchestrator = FullOrchestrator(activityRunner, definitionLoader)

    val result = orchestrator.start(workflow, input, workflowId)

    assertEquals(expectedOutput, result)
}
```

### Testing Individual Processors

```kotlin
@Test
fun `should process do task`() {
    val node = createTestNode<DoTask>(doTaskDefinition)
    val processor = DoProcessor(node)

    val result = processor.getNextStepInfo(
        state = DoState(startedAt = now, index = 0),
        dataset = input,
        scope = emptyScope,
        direction = Direction.FROM_PARENT
    )

    assertNotNull(result.rawOutput)
}
```

---

## Common Debugging

### Workflow Not Parsing

1. Check YAML syntax (indentation, colons)
2. Verify task names match DSL spec
3. Check `document` section has `name` and `version`
4. Enable debug logging on `DefinitionCache`

### Node Not Found

1. Verify `NodePosition` path matches tree structure
2. Check `Token` usage (DO, TRY, CATCH, etc.)
3. Use `workflow.getNode(position)` to debug
4. Print tree with `node.toMermaidGraph()`

### Expression Evaluation Fails

1. Verify JQ syntax (use jq CLI to test)
2. Check scope variables are available
3. Verify input data structure matches expression
4. Check for null values in path
