# Lemline Core

The `lemline-core` module is the heart of the Lemline workflow engine. It provides a pure functional, stateless orchestrator capable of executing Serverless Workflow definitions with support for both synchronous and asynchronous execution modes.

## Overview

Lemline Core implements a robust workflow engine that emphasizes:

*   **Pure Functional Design**: The orchestrator is stateless. It takes the current state and an event/input, and returns the next state and side effects. State is managed externally.
*   **Serverless Workflow Support**: Native support for the [Serverless Workflow Specification](https://serverlessworkflow.io/), allowing you to define workflows using standard JSON or YAML.
*   **Step-by-Step Execution**: The orchestrator executes one step at a time, returning control after each step. This enables distributed execution where state can be persisted and resumed.

## Key Components

### StepByStepOrchestrator
The `StepByStepOrchestrator` is the main entry point. It handles:
*   Starting new workflow instances.
*   Resuming workflows from a specific task or state.
*   Managing the flow of data (inputs/outputs) between nodes.
*   Handling error propagation and `try/catch` logic.

### TaskStates
State is tracked via `TaskStates`, a map of `NodePosition` to `NodeState`. This allows the engine to know exactly where each parallel branch or sub-workflow is in its execution lifecycle.

### Processors
Each node type (e.g., `Operation`, `Switch`, `Sleep`) has a corresponding `Processor` that implements its specific logic. Processors are stateless and handle the "enter", "execute", and "leave" phases of a node.

## Implementation Strategy

### Workflow Tree Structure
The workflow is represented as a tree of `Node<T>` objects. Each node wraps a Serverless Workflow `TaskBase` definition (e.g., `DoTask`, `SwitchTask`).
*   **Lazy Parsing**: Children of a node (e.g., steps in a `do` block) are parsed lazily. This allows for efficient handling of large workflow definitions.
*   **Positioning**: Each node has a unique `NodePosition` which serves as a pointer (e.g., `root.do.0.switch.case1`).

### Traversal & Execution
The execution engine uses a pure functional approach implemented in `NodeProcessor`.
*   **Stateless Processors**: `NodeProcessor` instances are stateless. They receive the current state and input, and return the next step.
*   **Lifecycle**:
    1.  **enterFromParent**: Called when entering a node for the first time. It initializes state, validates input, and determines if the node should execute (checking `if` conditions).
    2.  **enterFromChild**: Called when a child node completes and returns control to the parent. It handles flow directives (continue, break, etc.).
*   **NextStepInfo**: The result of a step execution is `NextStepInfo`, which contains:
    *   The updated state of the current node.
    *   The next node to execute (child or parent).
    *   Flow directives for control flow.

### Spec Implementation
The mapping between Serverless Workflow specification and implementation is direct:
*   **TaskBase**: The SDK's `TaskBase` types are used directly in `Node<T>`.
*   **Processors**: Each task type has a specific processor (e.g., `DoProcessor` for `DoTask`, `SwitchProcessor` for `SwitchTask`).
*   **Control Flow**: Logic for `switch`, `foreach`, `try/catch` is handled by their respective processors, ensuring strict adherence to the spec's semantics.

## Usage

### Adding Dependency

```kotlin
implementation(project(":lemline-core"))
```

### Starting a Workflow

```kotlin
import com.lemline.core.orchestrator.StepByStepOrchestrator

val workflow = ... // Load your Serverless Workflow definition
val input = buildJsonObject { put("key", "value") }

// Execute one step
val result = StepByStepOrchestrator.start(
    workflow = workflow,
    workflowInput = input
)
```

### Resuming a Workflow

```kotlin
// Resume from a saved state and execute next step
val result = StepByStepOrchestrator.resume(
    workflow = workflow,
    taskStates = savedTaskStates,
    rawInput = input
)
```

## Dependencies

This module relies on:
*   **Serverless Workflow SDK**: For parsing and validating workflow definitions.
*   **Ktor Client**: For making HTTP requests within workflows.
*   **Kotlin Serialization**: For JSON handling and state serialization.
*   **Coroutines**: For non-blocking execution.
