# [ADR-0002] Workflow Execution Model

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires a well-defined execution model to handle workflow instances, state management, and transitions between workflow states. We needed to decide on an execution model that would be efficient, maintainable, and aligned with the Serverless Workflow specification.

## Decision

We have decided to implement a node-based workflow execution model with the following characteristics:

### Core Components

1. **Node Graph Structure**: 
   - Each workflow is represented as a directed graph of `Node<T>` objects
   - Nodes are generic and typed by their task type (e.g., `Node<DoTask>`, `Node<CallHTTP>`)
   - Nodes maintain parent-child relationships forming a tree structure
   - Each node knows its position, task details, and name

2. **Precise Position Tracking**:
   - Each node has a `NodePosition` that uniquely identifies its location in the workflow
   - Positions use JSON pointer format (e.g., "/do/0/doSomething", "/do/1/try/catch/do")
   - Positions support navigation: adding indexes, tokens, names, and retrieving parent positions
   - Custom tokens (DO, TRY, CATCH, etc.) help structure and identify special nodes

3. **State-Preserving Execution**:
   - Each node has a corresponding `NodeInstance<T>` during execution
   - `NodeState` objects capture execution state (inputs, outputs, variables, indexes)
   - State can be serialized for persistence and deserialized for resumption

4. **Two-Phase Execution Model**:
   - Control flow nodes are executed synchronously in sequence
   - Activity nodes (I/O operations) may pause execution and are resumed later
   - `WorkflowInstance.run()` implements the main execution algorithm with error handling

5. **Expression Evaluation**:
   - JQ expressions are used for data transformation and condition evaluation
   - Hierarchical scopes provide access to task context, workflow context, and variables

6. **Error Handling Framework**:
   - Structured error handling through `TryInstance` nodes
   - Support for retries with configurable backoff and jitter
   - Catch blocks for error handling

### Key Classes

1. **WorkflowInstance**: 
   - Controls overall workflow execution 
   - Maintains root node and execution state
   - Implements the main execution loop with error handling

2. **Node<T>**: 
   - Immutable representation of a workflow node
   - Generic type parameter specifies task type
   - Contains position, task definition, name, and parent reference

3. **NodeInstance<T>**: 
   - Runtime representation of a node during execution
   - Manages state transformations and child execution
   - Implements expression evaluation and error handling

4. **NodePosition**: 
   - Uniquely identifies a node's position in the workflow
   - Provides navigation through the workflow structure
   - Supports serialization for persistence

5. **NodeState**: 
   - Contains execution state variables
   - Captures inputs, outputs, variables, and indexes
   - Designed for serialization/deserialization

### Execution Algorithm

1. **Workflow Start**: 
   - Create a `WorkflowInstance` with initial state
   - Set status to RUNNING
   - Begin execution at the root node

2. **Main Loop**: 
   - Execute nodes until completion, waiting state, or error
   - Process flow nodes (DoInstance, ForInstance, etc.) synchronously
   - When reaching an activity node, execute it and potentially pause execution
   - Handle errors through TryInstance with retry logic

3. **State Management**:
   - Track execution state in NodeState objects
   - Transform inputs and outputs using expressions
   - Update parent node outputs based on child execution results

4. **Completion**:
   - Workflow completes when all nodes have executed
   - Execution may pause at wait nodes or listener nodes
   - Final output is available from the root node

### Scope Evaluation

The execution model implements a hierarchical scoping system that provides contextual data to expressions:

1. **Scope Hierarchy**:
   - Each `NodeInstance` provides a `scope` property that builds a context for expression evaluation
   - Scopes form a hierarchy: child scopes incorporate their parent scopes
   - The `RootInstance` provides the base workflow scope containing global context

2. **Scope Components**:
   - **Task Context**: Information about the current task (name, reference, definition, timestamps)
   - **Node Variables**: Task-specific variables (e.g., loop counters in `ForInstance`)
   - **Workflow Context**: Global workflow state exported by tasks
   - **Secrets**: Protected values accessible to expressions but not exported
   - **Runtime Information**: System-provided metadata about the execution environment

3. **Scope Building Process**:
   - The `NodeInstance.scope` property dynamically builds the current scope
   - The scope is constructed as a `JsonObject` by merging:
     - Current node variables
     - Task descriptor (input, output, metadata)
     - Parent scope (recursively)
   - Special nodes like `ForInstance` add additional variables (e.g., current item and index)
   - The `RootInstance` adds workflow context, secrets, and runtime information

4. **Scope Usage**:
   - Expression evaluation uses the full hierarchical scope
   - Conditions (`if`, `when`, etc.) are evaluated against this scope
   - Data transformations leverage the scope for context-aware processing
   - Error handling conditions access error information in scope

### Data Flow

The execution model implements the DSL data flow specifications with these key features:

1. **Data Transformation Chain**:
   - **rawInput** → **transformedInput** → **execution** → **rawOutput** → **transformedOutput**
   - Each node processes data through this transformation chain
   - Inputs and outputs are represented as `JsonElement` objects for flexibility

2. **Input Processing**:
   - `rawInput` is the original input data passed to the node
   - `transformedInput` is the result of applying the input transformation expression
   - Input transformation happens in `NodeInstance.start()` method
   - Input schema validation occurs before transformation if a schema is specified

3. **Output Processing**:
   - `rawOutput` is the direct result of node execution
   - `transformedOutput` is the result of applying the output transformation
   - Output processing happens in `NodeInstance.complete()` method
   - Output schema validation occurs after transformation if a schema is specified

4. **Context Updates**:
   - The `export.as` directive updates the global workflow context
   - Exported data is validated against the `export.schema` if specified
   - Context updates happen in `NodeInstance.complete()`
   - Updated context becomes available to subsequent node executions

5. **Data Flow Between Nodes**:
   - Parent nodes receive the transformed output of their children
   - Flow nodes pass their output as input to subsequent nodes
   - When transitioning between nodes, the previous node's output becomes the next node's input
   - Special nodes like `ForInstance` and `TryInstance` modify this flow with additional context

6. **Expression Evaluation**:
   - JQ expressions transform data at various points in the flow
   - The `evalString`, `evalBoolean`, `evalList`, and `evalObject` methods handle type-specific transformations
   - Expressions have access to the full hierarchical scope

### Error Management

The execution model implements a comprehensive error handling system:

1. **Error Representation**:
   - Errors are represented as `WorkflowError` objects with:
     - Error type (e.g., CONFIGURATION, VALIDATION, EXPRESSION, RUNTIME)
     - Error title, details, and status code
     - Position information identifying where the error occurred

2. **Error Propagation**:
   - Errors are raised using the `NodeInstance.raise()` method
   - Propagation immediately stops node execution
   - `WorkflowException` wraps the error and captures raising/catching information
   - Errors bubble up through the node hierarchy until caught or reaching the workflow level

3. **Error Handling Framework**:
   - `TryInstance` nodes provide structured error handling
   - The `isCatching()` method evaluates if an error should be caught based on:
     - Error type matching
     - Status code matching
     - Conditional expressions (`when` and `exceptWhen`)
   - Caught errors are made available in the scope via the `as` property

4. **Retry Mechanism**:
   - `TryInstance` implements a sophisticated retry system
   - Retry policies can be defined inline or referenced from workflow definition
   - Configurable retry parameters:
     - Maximum attempts (`limit.attempt.count`)
     - Attempt duration limits (`limit.attempt.duration`)
     - Total duration limit (`limit.duration`)
     - Conditional retries (`when` and `exceptWhen`)

5. **Backoff Strategies**:
   - Constant backoff: same delay between retries
   - Linear backoff: increasing delay proportional to attempt count
   - Exponential backoff: exponentially increasing delay
   - Jitter: randomized delay component to prevent thundering herd

6. **Error Recovery**:
   - Catch handlers (`catch.do`) execute when errors are caught but not retried
   - Error information is made available to catch handlers
   - Execution can continue after error handling
   - `RaiseInstance` allows explicit error generation within workflows

7. **Workflow-Level Error Handling**:
   - Uncaught errors mark the workflow as FAULTED
   - Execution stops at the node that raised the error
   - Original error details are preserved for diagnostics


## References

- [Serverless Workflow Specification](https://serverlessworkflow.io/)
- [Workflow Patterns](http://www.workflowpatterns.com/)