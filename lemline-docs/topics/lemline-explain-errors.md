# Understanding Error Handling in Lemline

This document explains how errors are managed, propagated, and handled throughout the Lemline workflow system.

## Error Handling Philosophy

Lemline's error handling is designed around a few key principles:

1. **Structured errors**: All errors have a consistent structure with rich metadata
2. **Typed exceptions**: Errors are categorized by type for precise handling
3. **Hierarchical propagation**: Errors bubble up through the workflow structure
4. **Recovery mechanisms**: Multiple strategies for handling and recovering from failures
5. **Transparent reporting**: Clear error information for debugging and monitoring

## Error Structure

Lemline implements the RFC 7807 Problem Details specification for consistent error representation:

<!-- workflow-example: data -->
```json
{
  "type": "https://open-workflow-specification.org/spec/1.0.0/errors/validation",
  "status": 400,
  "instance": "/do/0/validateInput",
  "title": "Input Validation Failed",
  "details": "The 'amount' field must be a positive number"
}
```

This structure provides:

- **type**: URI identifier for the error category
- **status**: Numeric code similar to HTTP status codes
- **instance**: JSON pointer to the location in the workflow where the error occurred
- **title**: Short human-readable error summary
- **details**: Detailed explanation specific to this occurrence

## Error Type Hierarchy

Lemline organizes errors into a hierarchy:

```
WorkflowError
├── Configuration (400)
│   └── Schema validation errors, invalid workflow definition
├── Validation (400)
│   └── Input/output data validation failures
├── Expression (400)
│   └── JQ expression evaluation errors
├── Authentication (401)
│   └── Authentication failures
├── Authorization (403)
│   └── Permission/access denied errors
├── Timeout (408)
│   └── Execution timeout errors
├── Communication (500)
│   └── External service communication failures
└── Runtime (500)
    └── General runtime execution errors
```

Each error type has:
- A unique URI identifier (e.g., `https://open-workflow-specification.org/spec/1.0.0/errors/timeout`)
- A default status code
- Specific handling semantics

## Error Sources

Errors can originate from multiple sources:

### 1. System-Generated Errors

The Lemline runtime automatically generates errors for common failure scenarios:

- **Schema Validation**: When input/output doesn't match the schema

  <code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

- **Expression Evaluation**: When JQ expressions fail

  <code-block lang="yaml" src="workflows/lemline-explain-errors/1-system-generated-errors.yaml" include-lines="8-10"/>

- **HTTP Communication**: When API calls fail

  <code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

- **Timeout Violations**: When operations exceed their timeouts

  <code-block lang="yaml" src="workflows/lemline-explain-errors/1-system-generated-errors-2.yaml" include-lines="8-8"/>

  > **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

### 2. Explicitly Raised Errors

Workflows can explicitly raise errors using the `raise` task:

<code-block lang="yaml" src="workflows/reference/typed-error.yaml"/>

This allows for domain-specific error conditions.

### 3. External System Errors

Errors from external systems are translated to Lemline's error model:

- **HTTP Status Codes**: Mapped to appropriate error types
  - 400-499: Typically ValidationError or related types
  - 500-599: Typically CommunicationError or RuntimeError

- **Database Errors**: Wrapped as SystemErrors with appropriate context

- **Messaging System Errors**: Wrapped as CommunicationErrors

## Error Propagation

When an error occurs, it follows a defined propagation path:

```
┌────────────┐     ┌────────────┐     ┌────────────┐     ┌────────────┐
│ Error      │     │ Check      │     │ Search for │     │ Workflow   │
│ Occurs     │ ──> │ Local Try  │ ──> │ Parent Try │ ──> │ Fails      │
│ at Node    │     │ Blocks     │     │ Blocks     │     │ (if not    │
└────────────┘     └────────────┘     └────────────┘     │ caught)    │
                                                         └────────────┘
```

1. The error occurs at a specific node
2. Lemline checks if the node is within a `try` block with a matching `catch`
3. If not caught, the error propagates up to any parent `try` blocks
4. If no matching handler is found, the workflow execution fails

## Error Handling Mechanisms

Lemline provides several mechanisms for handling errors:

### 1. Try-Catch Blocks

The primary error handling mechanism:

<code-block lang="yaml" src="workflows/lemline-explain-errors/1-try-catch-blocks.yaml" include-lines="7-23"/>

Key components:
- **do block**: Contains tasks that might fail
- **catch blocks**: Define how to handle specific errors
- **error.with**: Criteria for matching errors
- **error.as**: Variable name to store the caught error
- **do**: Compensation logic when an error is caught

### 2. Retry Policies

Automated retry for transient failures:

<code-block lang="yaml" src="workflows/lemline-explain-errors/2-retry-policies.yaml" include-lines="7-12"/>

When an error occurs:
1. The error is caught
2. The retry policy is evaluated
3. If retries remain, the system waits according to the backoff strategy
4. The entire `do` block is re-executed

### 3. Fallback Patterns

Define explicit fallback behavior:

<code-block lang="yaml" src="workflows/lemline-explain-errors/3-fallback-patterns.yaml" include-lines="7-24"/>

This pattern allows graceful degradation when preferred data sources fail.

### 4. Dead Letter Queues

Dead letter queues are a runner feature, not a workflow setting. When a runner cannot process a message from the broker, the message goes to a dead-letter destination (for Kafka, `<source-topic>.dlq`; for RabbitMQ and PGMQ, the configured or derived DLQ queue). Messages received for a namespace that no runner serves are parked in the failures table and can be re-sent with `lemline failures replay`.

## Designing Error-Resilient Workflows

Best practices for error-resilient workflows:

### 1. Define Expected Errors

Explicitly define expected error conditions:

<code-block lang="yaml" src="workflows/lemline-explain-errors/1-define-expected-errors.yaml" include-lines="6-15"/>

### 2. Implement Structured Error Handling

Structure your workflow with proper error handling:

<code-block lang="yaml" src="workflows/lemline-explain-errors/2-implement-structured-error-handling.yaml" include-lines="7-31"/>

### 3. Use Appropriate Retry Strategies

Choose retry strategies based on error types:

- **Immediate retry**: For race conditions
- **Constant delay**: For brief outages
- **Exponential backoff**: For congested services
- **Exponential backoff with jitter**: For distributed systems

### 4. Implement Graceful Degradation

Design workflows to handle partial failures:

<code-block lang="yaml" src="workflows/lemline-explain-errors/4-implement-graceful-degradation.yaml" include-lines="7-29"/>

### 5. Provide Comprehensive Error Context

Ensure errors contain useful diagnostic information:

<code-block lang="yaml" src="workflows/lemline-explain-errors/5-provide-comprehensive-error-context.yaml" include-lines="7-13"/>

## Debugging and Monitoring Errors

Lemline provides tools for error analysis:

### Error Logging

Detailed contextual logging:

Every runner log line is prefixed with the workflow namespace, name, version and instance id, for example:

```
2023-06-15 14:32:45.123 ERROR [acme/order-processing:1.0.0] [123e4567-e89b-12d3-a456-426614174000] [c.l.c.p.AbstractProcessor] ...
```

### Error Metrics

Runners expose processing metrics at `/q/metrics`, including failure counters per channel such as `lemline_messaging_commands_processing_failed_total`, tagged by reason, workflow name and version. Faulted instances and retried tasks are visible through the `workflow.faulted`, `task.faulted` and `task.retried` lifecycle events.

### Error Tracing

The `workflow.faulted` and `task.faulted` lifecycle events of an instance carry the full error (`type`, `status`, `title`, `instance`, `detail`), where `instance` is the position of the failing task in the workflow. They are visible in the dashboard and, with lifecycle analytics enabled, stored in the analytics database.

## Related Resources

- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [Implementing Retry Mechanisms](lemline-howto-retry.md)
- [Custom Error Types](lemline-howto-custom-errors.md)
- [Debugging Workflows](lemline-howto-debug.md)
- [Resilience Patterns](dsl-resilience-patterns.md)