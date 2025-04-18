# [ADR-0002] Workflow Execution Model

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires a well-defined execution model to handle workflow instances, state management, and transitions between workflow states. We needed to decide on an execution model that would be efficient, maintainable, and aligned with the Serverless Workflow specification.

## Decision

We have decided to implement a node-based workflow execution model with the following characteristics:

1. **Workflow Instance**: Each workflow execution is represented by a `WorkflowInstance` object that maintains the current state, data, and execution context.

2. **Node-based Execution**: Workflows are represented as a graph of nodes, where each node represents a specific action or decision point in the workflow. The execution progresses by traversing this graph.

3. **Node Position Tracking**: The current position in the workflow is tracked using a `NodePosition` object, which allows for precise identification of the current execution point, even in nested structures.

4. **State Management**: The state of a workflow instance is maintained in a structured way, allowing for persistence and resumption of execution.

5. **Event-driven Transitions**: Transitions between nodes can be triggered by events, allowing for asynchronous and event-driven workflow execution.

6. **Expression Evaluation**: Conditions and expressions within the workflow are evaluated using a dedicated expression evaluation mechanism.

7. **Error Handling**: Errors during workflow execution are handled through a structured error handling mechanism, including retry and compensation logic.

## Consequences

### Positive

- **Alignment with Specification**: The execution model closely aligns with the Serverless Workflow specification, making it easier to implement the full specification.
- **Flexibility**: The node-based approach provides flexibility in implementing various workflow patterns.
- **Maintainability**: The clear separation of concerns between different aspects of workflow execution makes the code more maintainable.
- **Debuggability**: The precise tracking of node positions makes it easier to debug workflow execution issues.
- **Extensibility**: New node types and execution behaviors can be added without changing the core execution model.

### Negative

- **Complexity**: The node-based execution model introduces some complexity, especially for handling nested structures and parallel execution.
- **Performance Overhead**: Tracking detailed execution state and node positions introduces some performance overhead.
- **Learning Curve**: Developers need to understand the node-based execution model to effectively work with the codebase.

## Alternatives Considered

### State Machine Approach

A traditional state machine approach would represent workflows as a set of states with transitions between them. This approach was rejected because:
- It would be less flexible for representing complex workflow patterns, especially those with nested structures.
- It would be harder to align with the Serverless Workflow specification, which uses a more expressive model.
- It would make it more difficult to implement features like parallel execution and complex branching logic.

### Interpreter-based Approach

An interpreter-based approach would interpret workflow definitions directly without converting them to an intermediate representation. This approach was rejected because:
- It would make it harder to implement features like persistence and resumption of workflow execution.
- It would be less efficient for workflows with repeated execution of the same nodes.
- It would make debugging and monitoring more challenging.

## References

- [Serverless Workflow Specification](https://serverlessworkflow.io/)
- [Workflow Patterns](http://www.workflowpatterns.com/)