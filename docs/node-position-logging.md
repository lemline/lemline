# Dynamic Node Position Logging in Workflow Execution

## Overview

This document describes the implementation of dynamic node position logging in the Lemline workflow engine. The goal is to ensure that logs always contain the current node position as it evolves during workflow execution, which is crucial for debugging and monitoring workflow execution.

## Problem Statement

In the `WorkflowInstance.run()` method, the workflow context for logging was set once at the beginning of the method with the initial node position. However, as the workflow execution progresses, the current node position changes, but the logging context wasn't updated to reflect these changes. This resulted in logs showing the initial node position throughout the entire workflow execution, making it difficult to track the actual execution path.

## Solution

The solution involves two main components:

1. Adding helper functions to update the MDC context directly
2. Updating the node position in the logging context at key points during workflow execution

### Helper Functions

Two new functions were added to the `logger.kt` file:

```kotlin
/**
 * Updates a single context value in the current thread's logging context.
 * This is useful for updating dynamic values like node position during workflow execution.
 *
 * @param key The context key to update
 * @param value The new value for the context key
 */
fun updateLoggingContext(key: String, value: String?) {
    if (value != null) {
        MDC.put(key, value)
    } else {
        MDC.remove(key)
    }
}

/**
 * Updates the node position in the current thread's logging context.
 * This is useful for tracking the current position in a workflow as it evolves.
 *
 * @param nodePosition The current node position
 */
fun updateNodePosition(nodePosition: String?) {
    updateLoggingContext(LogContext.NODE_POSITION, nodePosition)
}
```

These functions allow updating the node position in the MDC context directly, without using nested inline lambdas that would cause issues with break statements.

### Updating Node Position During Workflow Execution

The `WorkflowInstance.run()` method was modified to update the node position at key points during workflow execution:

1. Before running the current node
2. After an exception occurs
3. After setting a retry
4. After setting a catch handler
5. After moving to the next node
6. Before setting the final currentNodeInstance

Here's an example of how the node position is updated before running the current node:

```kotlin
// Update node position before running the current node
updateNodePosition(current.node.position.toString())
current = current.run()
```

## Benefits

This implementation ensures that logs always contain the current node position, which provides several benefits:

1. **Improved Debugging**: Developers can easily track the execution path of a workflow by following the node positions in the logs.
2. **Better Monitoring**: Monitoring systems can use the node position to track workflow progress and identify bottlenecks.
3. **Enhanced Troubleshooting**: When errors occur, the logs will show the exact node position where the error occurred, making it easier to diagnose and fix issues.

## Example Log Output

With this implementation, log messages will include the current node position as it evolves during workflow execution:

```
2023-06-15 10:15:30.123 INFO [req123,wf456,corr789,root] [WorkflowConsumer] (main) Starting workflow execution
2023-06-15 10:15:30.124 DEBUG [req123,wf456,corr789,root.do[0]] [WorkflowConsumer] (main) Executing do task
2023-06-15 10:15:30.125 INFO [req123,wf456,corr789,root.do[1]] [WorkflowConsumer] (main) Executing next task
2023-06-15 10:15:30.126 WARN [req123,wf456,corr789,root.do[2]] [WorkflowConsumer] (main) Task failed, retrying
2023-06-15 10:15:30.127 INFO [req123,wf456,corr789,root.do[2].catch] [WorkflowConsumer] (main) Executing catch handler
2023-06-15 10:15:30.128 DEBUG [req123,wf456,corr789,root.do[3]] [WorkflowConsumer] (main) Workflow execution completed
```

## Conclusion

The dynamic node position logging implementation ensures that logs always contain the current node position as it evolves during workflow execution. This makes it easier to track the execution path of a workflow, diagnose issues, and monitor workflow progress.