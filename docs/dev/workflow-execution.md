# Workflow Execution Model

## Overview

Lemline implements a node-based workflow execution model for the Serverless Workflow DSL. The model is designed to:

- Represent workflows as directed graphs of typed nodes
- Track precise positions within a workflow
- Maintain execution state during workflow pauses
- Support expression evaluation with JQ
- Provide comprehensive error handling

## Core Components

### Node Graph Structure

```kotlin
// Node is the immutable definition of a workflow node
class Node<T : Task>(
    val position: NodePosition,
    val task: T,
    val name: String,
    val parent: Node<*>?
)
```

- Each workflow is represented as a directed graph of `Node<T>` objects
- Nodes are generic and typed by their task type (e.g., `Node<DoTask>`, `Node<CallHTTP>`)
- Nodes maintain parent-child relationships forming a tree structure
- Each node knows its position, task details, and name

### Precise Position Tracking

```kotlin
class NodePosition(val path: String) {
    fun addIndex(index: Int): NodePosition = NodePosition("$path/$index")
    fun addToken(token: String): NodePosition = NodePosition("$path/$token")
    fun addName(name: String): NodePosition = NodePosition("$path/$name")
    fun parent(): NodePosition = NodePosition(path.substringBeforeLast("/"))
}
```

- Each node has a `NodePosition` that uniquely identifies its location in the workflow
- Positions use JSON pointer format (e.g., "/do/0/doSomething", "/do/1/try/catch/do")
- Positions support navigation: adding indexes, tokens, names, and retrieving parent positions
- Custom tokens (DO, TRY, CATCH, etc.) help structure and identify special nodes

### State-Preserving Execution

```kotlin
data class NodeState(
    val inputs: JsonElement? = null,
    val transformedInputs: JsonElement? = null,
    val outputs: JsonElement? = null,
    val transformedOutputs: JsonElement? = null,
    val variables: JsonObject? = null,
    val indexes: JsonObject? = null
)
```

- Each node has a corresponding `NodeInstance<T>` during execution
- `NodeState` objects capture execution state (inputs, outputs, variables, indexes)
- State can be serialized for persistence and deserialized for resumption

### Two-Phase Execution Model

The execution model operates in two phases:

1. **Control Flow Phase**:
   - Control flow nodes (Do, For, Switch, etc.) are executed synchronously in sequence
   - These operations are CPU-bound and do not involve I/O

2. **Activity Phase**:
   - Activity nodes (HTTP calls, service invocations, etc.) may involve I/O
   - Execution may pause at these nodes, to be resumed later
   - State is preserved during pauses

## Execution Algorithm

The main execution algorithm is implemented in `WorkflowInstance.run()`:

```kotlin
fun run(resumePosition: NodePosition? = null): NodeState? {
    if (resumePosition != null) {
        // Resume execution from a specific position
        val nodeInstance = nodeInstances[resumePosition]
            ?: throw WorkflowException("Cannot resume: no node at position $resumePosition")
        return runNode(nodeInstance)
    } else {
        // Start execution from the beginning
        return runNode(rootInstance)
    }
}

private fun runNode(nodeInstance: NodeInstance<*>): NodeState? {
    try {
        // Initialize node if not already started
        if (!nodeInstance.isStarted) {
            nodeInstance.start()
        }
        
        // Handle the node's execution status
        return when (nodeInstance.status) {
            WAITING -> {
                // Node is waiting for some external event
                null
            }
            
            RUNNING -> {
                // Continue executing this node
                nodeInstance.execute()
                
                if (nodeInstance.status == COMPLETED) {
                    // Node finished, process its output
                    nodeInstance.complete()
                    
                    // If this node has a next node, run it
                    nodeInstance.next?.let { 
                        runNode(it)
                    } ?: nodeInstance.state
                } else {
                    // Node still running or waiting
                    null
                }
            }
            
            COMPLETED -> {
                // Node already completed, return its state
                nodeInstance.state
            }
            
            FAULTED -> {
                // Node execution failed, propagate the error
                null
            }
        }
    } catch (e: WorkflowException) {
        // Handle errors through the Try/Catch mechanism
        handleError(nodeInstance, e)
        return null
    }
}
```

### Node Execution Lifecycle

Each node goes through the following lifecycle:

1. **Start**: The node is initialized with input data and transforms it using the input transformation expression
2. **Execute**: The node performs its specific operation (e.g., a loop, condition, or external call)
3. **Complete**: The node processes its output, applies output transformations, and updates the workflow context
4. **Status Transitions**: CREATED → RUNNING → (WAITING) → COMPLETED or FAULTED

## Expression Evaluation

Lemline uses JQ expressions for data transformation and condition evaluation:

```kotlin
fun evalString(expression: String?, context: JsonElement): String? {
    if (expression == null) return null
    val result = jqEngine.execute(expression, context) ?: return null
    return result.asString
}

fun evalBoolean(expression: String?, context: JsonElement): Boolean {
    if (expression == null) return true
    val result = jqEngine.execute(expression, context) ?: return false
    return result.asBoolean
}
```

Expressions have access to:
- Task context (task details, input/output)
- Workflow context (global state)
- Local variables (e.g., loop counters)
- Runtime information

## Error Handling Framework

### Error Representation

```kotlin
data class WorkflowError(
    val type: ErrorType,
    val title: String,
    val details: String? = null,
    val status: Int? = null,
    val source: NodePosition? = null
)

enum class ErrorType {
    CONFIGURATION,
    VALIDATION,
    EXPRESSION,
    RUNTIME
}
```

### Try-Catch Mechanism

```kotlin
class TryInstance(
    position: NodePosition,
    task: TryTask,
    parent: NodeInstance<*>?
) : NodeInstance<TryTask>(position, task, parent) {
    // The main do block
    val doInstance: NodeInstance<*> = createChild(task.do, position.addToken(DO))
    
    // Optional catch blocks
    val catches: List<CatchInstance> = task.catch.mapIndexed { index, catchDef ->
        CatchInstance(position.addToken(CATCH).addIndex(index), catchDef, this)
    }
    
    override fun execute(): NodeState {
        try {
            // Execute the main do block
            val result = doInstance.execute()
            return result
        } catch (e: WorkflowException) {
            // Find a matching catch block
            val catcher = catches.firstOrNull { it.isCatching(e.error) }
            
            if (catcher != null) {
                // Execute the catch block
                val catchError = e.error.copy(source = e.position)
                catcher.setError(catchError)
                return catcher.execute()
            } else {
                // No matching catch, rethrow the error
                throw e
            }
        }
    }
}
```

### Retry Mechanism

```kotlin
fun retry(error: WorkflowError): Boolean {
    // Check if we've exceeded retry limits
    if (retryCount >= retryPolicy.maxAttempts) return false
    
    // Calculate next retry delay
    val delay = calculateBackoff(retryCount, retryPolicy)
    
    // Schedule retry
    retryCount++
    retryScheduler.scheduleRetry(this, delay)
    return true
}
```

Retry policies support:
- Maximum attempts (`limit.attempt.count`)
- Attempt duration limits (`limit.attempt.duration`)
- Total duration limit (`limit.duration`)
- Conditional retries (`when` and `exceptWhen`)
- Various backoff strategies (constant, linear, exponential) with jitter

## Extension Points

### Adding a New Node Type

To add a new node type:

1. Define the task model in the DSL:
```kotlin
data class MyCustomTask(
    val input: String?,
    val output: String?,
    val myParameter: String
) : Task
```

2. Create a node instance implementation:
```kotlin
class MyCustomInstance(
    position: NodePosition,
    task: MyCustomTask,
    parent: NodeInstance<*>?
) : NodeInstance<MyCustomTask>(position, task, parent) {
    override fun execute(): NodeState {
        // Implement your custom execution logic
        
        // Set output when done
        val result = JsonPrimitive("result")
        state = state.copy(outputs = result)
        status = COMPLETED
        
        return state
    }
}
```

3. Register the node type in the workflow parser:
```kotlin
class WorkflowParser {
    fun parseNode(definition: JsonObject, position: NodePosition): Node<*> {
        // Existing node type handling
        
        // Add your custom node type
        if (definition.containsKey("myCustom")) {
            val taskDef = definition.getObject("myCustom")
            val task = MyCustomTask(
                input = taskDef.getString("input"),
                output = taskDef.getString("output"),
                myParameter = taskDef.getString("myParameter") ?: error("myParameter is required")
            )
            return Node(position, task, name, parent)
        }
    }
}
```

### Adding a New Expression Engine

To add support for a different expression language:

1. Implement the `ExpressionEngine` interface:
```kotlin
interface ExpressionEngine {
    fun execute(expression: String, context: JsonElement): JsonElement?
}

class MyExpressionEngine : ExpressionEngine {
    override fun execute(expression: String, context: JsonElement): JsonElement? {
        // Implement your expression language evaluation
        return result
    }
}
```

2. Configure the engine in the workflow instance builder:
```kotlin
class WorkflowInstanceBuilder {
    var expressionEngine: ExpressionEngine = JqExpressionEngine()
    
    fun build(): WorkflowInstance {
        return WorkflowInstance(
            root = root,
            expressionEngine = expressionEngine
        )
    }
}
```

## Debugging Workflows

### Logging

The execution model includes detailed logging at key points:

- Node transitions (CREATED → RUNNING → COMPLETED)
- Expression evaluation results
- Input/output transformations
- Error handling and retries

Enable debug logging for detailed execution information:
```properties
quarkus.log.category."com.lemline.core.execution".level=DEBUG
```

### Visualization

You can visualize workflow execution by:

1. Enabling execution tracing:
```properties
lemline.workflow.tracing.enabled=true
```

2. Using the trace API to retrieve execution details:
```kotlin
val trace = workflowService.getTrace(instanceId)
```

3. Rendering the trace data to show:
- Node execution order
- Time spent in each node
- Data transformations
- Error paths and retries

## Troubleshooting

### Common Issues

- **Expression evaluation errors**: Check JQ syntax and context availability
- **Missing state**: Ensure `NodeState` is correctly serialized/deserialized
- **Infinite loops**: Validate termination conditions in loop nodes
- **Unexpected errors**: Ensure error handling is properly implemented

### Best Practices

- Always validate inputs with schemas
- Use clear node names for easier debugging
- Keep workflows modular with reusable sub-workflows
- Test workflows with mock services for I/O operations
- Use tracing in development to understand execution paths 