# Lemline Error Handling Examples

This document provides examples of error handling and resilience patterns in Lemline workflows. Robust error handling is essential for building reliable workflows that can gracefully recover from failures when interacting with external systems, processing data, or executing business logic.

## Basic Try-Catch Pattern

The fundamental error handling mechanism in Open Workflow DSL is the try-catch block, which allows workflows to catch and handle exceptions.

### Simple Try-Catch Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/simple-try-catch-example.yaml"/>

This example demonstrates:
- Wrapping a potentially failing HTTP call in a `try` block
- Catching a specific error type (communication error with 404 status)
- The workflow continues after the error is caught

Key aspects of basic try-catch:
- Execution continues after the catch block
- If an error occurs that doesn't match the `errors` criteria, it will propagate up
- If no error occurs, the `catch` block is skipped

## Error Handling with Custom Actions

When an error occurs, you often need to perform specific actions in response, such as logging, notification, or fallback behavior.

### Try-Catch with Custom Error Handling

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/try-catch-with-custom-error-handling.yaml"/>

This example demonstrates:
- Capturing the error in a variable using `as: error`
- Executing custom actions in the catch block using `do`
- Emitting an event for notification purposes
- Storing the error in the workflow context
- Conditionally executing subsequent tasks based on error state

## Retry Mechanisms

For transient failures, retry mechanisms allow workflows to automatically attempt the operation again after a delay.

### Inline Retry Configuration

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/inline-retry-configuration.yaml"/>

This example demonstrates:
- Retrying a failed operation with a specific error type (503 Service Unavailable)
- Initial delay of 3 seconds before the first retry
- Exponential backoff for subsequent retries
- Maximum of 5 retry attempts

Key aspects of retry configuration:
- `delay`: The initial delay before the first retry
- `backoff`: Strategy for increasing delay between retries (exponential recommended)
- `limit`: Constraints on retry attempts, typically by count or duration

### Reusable Retry Configuration

For consistent retry policies across multiple operations, define reusable retry configurations:

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/reusable-retry-configuration.yaml"/>

This example demonstrates:
- Defining a reusable retry policy named `default`
- Referencing the reusable policy in catch blocks
- Maintaining consistent retry behavior across the workflow

## Conditional Retry

Sometimes you want to retry only under specific conditions, rather than for all errors of a certain type.

### Conditional Retry Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/conditional-retry-example.yaml"/>

This example demonstrates:
- Capturing the error in a variable using `as: paymentError`
- Using a condition with `when` to determine if retry should occur
- Only retrying for server errors (5xx) or rate limiting (429)
- Customizing the retry policy for these specific conditions

## Error Type Hierarchies

The DSL supports hierarchical error types, allowing for more flexible error matching.

### Error Type Hierarchy Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/error-type-hierarchy-example.yaml"/>

This example demonstrates:
- Multiple catch blocks for different error types
- Hierarchical error matching (most specific to most generic)
- Different handling for each error category
- Catch blocks are evaluated in order, and the first matching block handles the error

## Compensating Transactions

In some workflows, you need to perform cleanup actions when an error occurs after some operations have already succeeded.

### Compensating Transaction Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/compensating-transaction-example.yaml"/>

This example demonstrates:
- A sequence of operations (inventory reservation, payment processing)
- Capturing the output of the first operation for later reference
- When payment fails, executing compensating actions to clean up
- Canceling the inventory reservation to maintain consistency
- Notifying the customer about the failure

## Circuit Breaker Pattern

The circuit breaker pattern prevents repeated calls to a failing service, reducing load and allowing it to recover.

### Circuit Breaker Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/circuit-breaker-example.yaml"/>

This example demonstrates:
- A retry configuration with circuit breaker settings
- `failureThreshold`: Number of failures before the circuit opens
- `recoveryTime`: How long to wait before allowing attempts again
- The workflow will stop retrying after the threshold and wait for recovery

## Timeout Handling

Timeouts prevent workflows from hanging indefinitely when external systems are unresponsive.

### Timeout Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/timeout-example.yaml"/>

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

This example demonstrates:
- Setting a timeout for an HTTP call
- Catching the specific timeout error type
- Custom handling for timeout conditions
- Exiting the workflow early with status information

## Comprehensive Error Handling Strategy

Combining these patterns creates a comprehensive error handling strategy:

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

This comprehensive example demonstrates:
- Nested try-catch blocks for different stages of processing
- Different error handling strategies based on error type
- Timeout handling for operations
- Compensating transactions (canceling reservations)
- Reusing retry definitions for communication errors
- Early workflow exit with appropriate status information
- Customer notifications for different error scenarios

## Best Practices for Error Handling

When implementing error handling in Lemline workflows:

1. **Categorize errors**: Distinguish between transient failures, permanent failures, and business exceptions
2. **Use hierarchy**: Organize error types hierarchically for flexible error matching
3. **Isolate dangerous operations**: Wrap risky operations in their own try-catch blocks
4. **Add context**: Capture and log relevant context information with errors
5. **Compensate for partial failures**: Always clean up after partial successes when an error occurs
6. **Consider idempotency**: Design operations to be safely retryable
7. **Set timeouts**: Prevent indefinite hanging by setting appropriate timeouts (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
8. **Provide good error messages**: Make error messages informative for operations and customers
9. **Implement circuit breakers**: Protect services from overload during persistent failures

## Conclusion

These error handling examples demonstrate how to build robust, resilient workflows in Lemline. By properly implementing try-catch blocks, retries, and compensating transactions, you can create workflows that gracefully handle failures and maintain system consistency.

For complex real-world examples that incorporate these error handling patterns, see the [Real-world Examples](lemline-examples-real-world.md) document.