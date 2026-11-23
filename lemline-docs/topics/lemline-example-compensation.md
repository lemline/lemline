# Compensation Pattern Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 authentication. The Lemline runtime does not execute these yet: such a task fails when it runs. Write those calls with `call: http` and `basic` or `bearer` authentication to run them on Lemline.


This document provides examples of implementing compensation patterns in Lemline workflows. Compensation patterns are essential for maintaining data consistency and handling partial failures in distributed systems by implementing actions that "undo" or "compensate" for previously completed steps when errors occur.

## Basic Compensation Pattern

### Simple Two-Phase Workflow with Compensation

This example demonstrates a simple order processing workflow with reservation and charge steps, with compensation for the reservation if the charge fails.

<code-block lang="yaml" src="workflows/lemline-example-compensation/simple-two-phase-workflow-with-compensation.yaml"/>

## Multi-Step Transaction Compensation

### Saga Pattern Implementation

This example implements the Saga pattern for a multi-step business process with compensation actions for each step.

<code-block lang="yaml" src="workflows/lemline-example-compensation/saga-pattern-implementation.yaml"/>

## Compensation with Retry

### Resilient Order Processing with Retries and Compensation

This example demonstrates combining retry policies with compensation actions for a more resilient workflow.

<code-block lang="yaml" src="workflows/lemline-example-compensation/resilient-order-processing-with-retries-and-compensation.yaml"/>

## Compensation Strategies for Different Resource Types

### Transaction Management with Mixed Resource Types

This example demonstrates different compensation strategies for various resource types (database, message queue, file system, etc.).

<code-block lang="yaml" src="workflows/lemline-example-compensation/transaction-management-with-mixed-resource-types.yaml"/>

## Asynchronous Compensation

### Long-Running Process with Asynchronous Compensation

This example demonstrates handling compensation for long-running processes that may require asynchronous cleanup.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

## Best Practices for Compensation Patterns

When implementing compensation patterns in Lemline workflows, consider these best practices:

1. **Resource Tracking**: Keep track of all resources allocated during workflow execution
2. **Compensate in Reverse Order**: Apply compensating actions in reverse order of the original operations
3. **Idempotent Compensation**: Design compensation actions to be safely retryable
4. **Partial Compensation**: Handle scenarios where only some steps need compensation
5. **Async Compensation**: For long-running processes, implement asynchronous compensation
6. **Compensation Logging**: Log all compensation actions for auditing and troubleshooting
7. **Escalation Policy**: Define clear escalation paths when compensation fails
8. **Independent Compensation**: Design compensation actions that don't depend on workflow state
9. **Explicit Cleanup**: Always explicitly clean up resources, don't rely on implicit cleanup
10. **Timeouts for Compensation**: Set appropriate timeouts for compensation actions (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)

## Conclusion

These examples demonstrate various compensation patterns for handling partial failures and maintaining data consistency in distributed workflows. By implementing proper compensation strategies, you can build resilient workflows that gracefully handle failures at any stage of execution.

Compensation patterns are essential for maintaining data integrity in distributed systems, preventing resource leaks, and ensuring that business processes remain in a consistent state even when technical failures occur.

For more examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.