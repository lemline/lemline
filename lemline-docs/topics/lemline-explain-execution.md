# Understanding Workflow Execution

This document explains how Lemline executes workflows, from parsing the definition to completion.

## Execution Model Overview

Lemline implements a node-based execution model for Serverless Workflow DSL:

1. **Parsing**: The workflow definition (YAML/JSON) is parsed into a structured object model
2. **Graph Construction**: A directed graph of nodes is created from the model
3. **State Management**: Each node maintains its own execution state
4. **Execution Flow**: Nodes execute in sequence according to the workflow definition
5. **Data Transformation**: Data flows through the workflow, being transformed by expressions

## Workflow Lifecycle

### 1. Initialization Phase

When a workflow definition is submitted to Lemline:

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Parse YAML │ ─> │  Validate   │ ─> │ Construct   │ ─> │  Register   │
│  or JSON    │    │  Schema     │    │ Node Graph  │    │  Workflow   │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

1. **Parsing**: The YAML/JSON is parsed into a data structure
2. **Schema Validation**: The structure is validated against the Serverless Workflow schema
3. **Node Graph Construction**: A directed graph of `NodeInstance` objects is created
4. **Registration**: The workflow is registered in the Lemline registry

### 2. Instantiation Phase

When a workflow instance is created:

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Create     │ ─> │  Validate   │ ─> │  Initialize │ ─> │ Begin       │
│  Instance   │    │  Input      │    │  Context    │    │ Execution   │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

1. **Instance Creation**: A new `WorkflowInstance` is created
2. **Input Validation**: Input data is validated against the input schema
3. **Context Initialization**: Initial data context is created with the input data
4. **Execution Start**: The execution begins with the root node

### 3. Execution Phase

During workflow execution:

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Execute    │ ─> │  Evaluate   │ ─> │  Determine  │
│  Current    │    │  State &    │    │  Next       │ ─┐
│  Node       │    │  Expressions│    │  Node       │  │
└─────────────┘    └─────────────┘    └─────────────┘  │
       ▲                                                │
       └────────────────────────────────────────────────┘
```

1. **Node Execution**: The current node is executed
2. **State Evaluation**: The node's state is evaluated, and expressions are processed
3. **Next Node Determination**: The next node is determined based on the current state
4. **Continuation**: The process repeats until completion or suspension

### 4. Completion Phase

When workflow execution finishes:

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Validate   │ ─> │  Construct  │ ─> │  Update     │ ─> │  Emit       │
│  Output     │    │  Result     │    │  State      │    │  Events     │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

1. **Output Validation**: Final data is validated against the output schema
2. **Result Construction**: The workflow result is constructed
3. **State Update**: The workflow state is updated to "completed"
4. **Event Emission**: Completion events are emitted

## Node-Based Execution Model

Lemline's core execution model is based on a graph of nodes representing workflow tasks:

### Node Structure

Each node in the workflow graph has:

```kotlin
class NodeInstance<T : Task> {
    val node: T                      // The task definition
    val position: NodePosition       // Unique position identifier
    val parent: NodeInstance<*>?     // Parent node (if any)
    val children: List<NodeInstance<*>> // Child nodes
    
    var state: NodeState             // Current execution state
    
    fun execute(): NodeState         // Execute the node
}
```

### Node Position

Node positions uniquely identify nodes within a workflow:

```
/do/0/try/do/1
 │  │ │   │  │
 │  │ │   │  └─ Second task in the do block
 │  │ │   └──── Do block within try
 │  │ └──────── Try task
 │  └────────── First task in the main do block
 └───────────── Root of workflow
```

This hierarchical addressing enables:
- Precise error reporting
- State tracking at specific positions
- Resume capability at exact positions

### Node State

Each node maintains its own state:

```kotlin
data class NodeState(
    val status: NodeStatus,         // READY, RUNNING, COMPLETED, SUSPENDED, FAILED
    val input: JsonElement,         // Input data
    val output: JsonElement,        // Output data
    val variables: Map<String, JsonElement>, // Local variables
    val error: WorkflowError?       // Error information (if failed)
)
```

## Data Flow and Context Management

Data flows through the workflow in a structured way:

### Hierarchical Scoping

Variables follow hierarchical scoping rules:

```
┌───────────────────────────────────────┐
│ Root Scope                             │
│ └───────────────────────────────────┐ │
│  │ Task A Scope                     │ │
│  │ └───────────────────────────┐    │ │
│  │  │ Nested Task B Scope      │    │ │
│  │  │                          │    │ │
│  │  └───────────────────────────┘    │ │
│  └───────────────────────────────────┘ │
└───────────────────────────────────────┘
```

When resolving variables, Lemline searches:
1. The current node's scope
2. Parent scopes (up to the root)

### Expression Evaluation

JQ expressions are evaluated against the current context:

```yaml
- setTax:
    set:
      taxAmount: "${ .orderTotal * .taxRate }"
```

The expression `.orderTotal * .taxRate` is evaluated with:
1. Current node's variables
2. Parent scope variables
3. Workflow input

### Data Transformation Flow

As data moves through the workflow:

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│  Input   │     │  Node A  │     │  Node B  │
│  Data    │ ──> │  Process │ ──> │  Process │ ──> ...
│          │     │          │     │          │
└──────────┘     └──────────┘     └──────────┘
```

Each node:
1. Receives input data
2. Processes and transforms it
3. Outputs result to next node

## Two-Phase Execution 

Lemline implements a two-phase execution model:

### Synchronous Execution Phase

Control flow nodes execute synchronously:
- `do` - Sequential tasks
- `switch` - Conditional branching
- `for` - Iterations
- `fork` - Parallel execution
- `try` - Error handling
- `set` - Variable assignment

These nodes complete their execution immediately.

### Asynchronous Execution Phase

Activity nodes may execute asynchronously:
- `callHTTP` - External API calls
- `callOpenAPI/callGRPC` - Remote service calls
- `wait` - Time-based waiting
- `listen` - Event waiting
- `run` - External process execution

These nodes can pause workflow execution, creating a "suspension point."

## Suspension and Resumption

When a workflow encounters an asynchronous operation:

### Suspension Process

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Encounter  │ ─> │  Save       │ ─> │  Create     │ ─> │  Wait for   │
│  Async Node │    │  State      │    │  Outbox     │    │  Event      │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

1. Workflow execution reaches an asynchronous node
2. Current state is saved to the database
3. An outbox record is created for reliable processing
4. Execution suspends, waiting for completion event

### Resumption Process

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Receive    │ ─> │  Load       │ ─> │  Process    │ ─> │  Continue   │
│  Event      │    │  State      │    │  Result     │    │  Execution  │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

1. A completion event is received
2. Workflow state is loaded from the database
3. Async operation result is processed
4. Execution continues from the suspension point

## Error Handling

Lemline provides robust error handling:

### Error Types

Errors are categorized by type:
- **Configuration**: Issues in the workflow definition
- **Validation**: Schema validation failures
- **Expression**: JQ expression evaluation errors
- **Runtime**: Errors during workflow execution

### Error Propagation

When an error occurs:

```
┌─────────┐     ┌─────────┐     ┌─────────┐
│ Error   │     │ Check   │     │ Parent  │
│ Occurs  │ ──> │ Current │ ──> │ Try     │ ──> ...
│         │     │ Try     │     │ Blocks  │
└─────────┘     └─────────┘     └─────────┘
```

1. Error information is captured
2. Current `try` block is checked for matching `catch`
3. If not caught, error propagates to parent `try` blocks
4. If never caught, workflow fails

### Retry Mechanism

When error is caught and retry is configured:

```
┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐
│ Error   │     │ Evaluate│     │ Wait    │     │ Retry   │
│ Caught  │ ──> │ Retry   │ ──> │ Backoff │ ──> │ Try     │
│         │     │ Policy  │     │ Period  │     │ Block   │
└─────────┘     └─────────┘     └─────────┘     └─────────┘
```

1. Retry policy is evaluated
2. If retry is allowed, a wait period is calculated
3. After the wait, the `try` block is re-executed

## Performance Considerations

The Lemline execution model is designed for:

### Efficiency

- **Minimal State**: Only essential state is persisted
- **Lazy Evaluation**: Expressions are evaluated only when needed
- **Targeted Persistence**: State is persisted only at suspension points

### Scalability

- **Stateless Execution**: Between suspension points, execution is stateless
- **Horizontal Scaling**: Multiple instances can process different workflows
- **Microservices Alignment**: Fits naturally into microservices architecture

### Reliability

- **Outbox Pattern**: Ensures reliable message delivery
- **Atomic Operations**: Database operations use transactions
- **Idempotent Processing**: Events can be safely processed multiple times

## Observability

The execution model provides comprehensive observability:

### Tracing

Each workflow execution generates a trace with:
- Node transitions
- Expression evaluations
- State changes
- Error occurrences

### Metrics

Key metrics include:
- Execution time per node
- Wait time at suspension points
- Error rates
- Retry counts

### Logging

Structured logging captures:
- Execution events
- Data transformations
- Error details
- External interactions

## Related Resources

- [Workflow Definition Guide](lemline-howto-define-workflow.md)
- [Error Handling](lemline-explain-errors.md)
- [Event-Driven Architecture](lemline-explain-event-driven.md)
- [Monitoring Workflows](lemline-howto-monitor.md)