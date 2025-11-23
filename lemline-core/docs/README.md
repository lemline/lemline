# Lemline Core

The `lemline-core` module is the heart of the Lemline workflow engine. It implements the Serverless Workflow DSL specification and provides the pure execution logic for workflows.

## 🧠 Core Philosophy

This module is designed as a **pure functional execution engine**. It does not handle:
- Persistence (database)
- Messaging (Kafka/RabbitMQ)
- Scheduling
- HTTP Server

Instead, it provides a `WorkflowOrchestrator` that takes the current state and input, executes the next step, and returns the new state and output. This separation allows the core to be extremely testable and embeddable in various runtimes (like the Quarkus-based `lemline-runner`).

## 🏗 Architecture

### 1. Nodes & Definitions (`com.lemline.core.nodes`)
Workflows are parsed into a tree of immutable `Node<T>` objects.
- Each `Node` wraps a Serverless Workflow DSL task (e.g., `CallHTTP`, `Switch`, `Do`).
- Nodes are identified by a unique `NodePosition` (e.g., `[0, "myTask", "do", 1]`).
- The structure is static and immutable once loaded.

### 2. Execution Engine (`com.lemline.core.orchestrator`)
The `WorkflowOrchestrator` is the main entry point. It implements a step-by-step execution loop:

```kotlin
// Pure functional signature
fun resumeFromTask(
    taskStates: TaskStates,
    node: Node<*>,
    rawInput: JsonElement
): WorkflowEvent
```

It handles:
- **Navigation**: Moving between nodes (Down -> Enter, Up -> Return).
- **Control Flow**: `if/else` (Switch), loops (For), parallel (Fork).
- **Error Handling**: `try/catch/retry` logic.
- **Data Transformation**: Evaluating JQ expressions.

### 3. Processors (`com.lemline.core.processors`)
Each node type has a corresponding `NodeProcessor`.
- `CallHttpProcessor`: Executes HTTP requests.
- `SwitchProcessor`: Evaluates conditions and chooses the next path.
- `WaitProcessor`: Calculates wait duration.
- `DoProcessor`: Manages sequential execution of child tasks.

Processors handle two main events:
- `enterFromParent`: Called when execution reaches this node from above.
- `enterFromChild`: Called when a child node completes and execution returns to this node.

### 4. State Management (`com.lemline.core.states`)
State is external to the nodes.
- `TaskStates`: A map of `NodePosition -> NodeState`.
- `WorkflowState`: The result of an execution step, which can be:
    - `WorkflowCompleted`: The workflow finished.
    - `TaskScheduled`: A task executed, proceed to the next one.
    - `WaitStarted`: The workflow needs to sleep (persisted to DB).
    - `RunWorkflowStarted`: A sub-workflow started.
    - `TaskFailed`: An error occurred.

## 🔄 Execution Flow

1. **Resume**: The runner calls `WorkflowOrchestrator.resume()`.
2. **Step**: The orchestrator finds the processor for the current node.
3. **Execute**: The processor executes its logic (e.g., make HTTP call, evaluate condition).
4. **Result**:
    - If the task completes immediately, it returns the next node.
    - If the task needs to wait (e.g., `delay`), it throws a `WaitException`.
    - If the task fails, it throws an exception.
5. **Event**: The orchestrator catches these signals and returns a `WorkflowEvent` to the runner.

## 🧪 Testing

The core module is tested extensively using unit tests that simulate workflow execution without external dependencies.
- `DataFlowTest.kt`: Verifies input/output transformation.
- `ControlFlowTest.kt`: Verifies branching and looping.
- `ErrorHandlingTest.kt`: Verifies retries and catch blocks.

## 📦 Key Packages

- `com.lemline.core.nodes`: Workflow definition tree.
- `com.lemline.core.orchestrator`: Execution engine.
- `com.lemline.core.processors`: Node-specific execution logic.
- `com.lemline.core.expressions`: JQ expression evaluation.
- `com.lemline.core.states`: Runtime state models.
