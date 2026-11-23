---
title: Error Handling
---

# Error Handling Tasks

Error handling tasks provide mechanisms to manage exceptions, failures, and unexpected conditions in your workflows.
They enable you to create resilient workflows that can detect, respond to, and recover from errors gracefully.

## Error Handling Task Types

| Task                       | Purpose                                                  |
|----------------------------|----------------------------------------------------------|
| [Try](dsl-task-try.md)     | Execute tasks with error handling and recovery logic     |
| [Raise](dsl-task-raise.md) | Explicitly throw errors to signal exceptional conditions |

## When to Use Error Handling Tasks

### Exception Handling with Try

Use the **Try** task when you need to:

- Catch and handle potential errors from risky operations
- Provide alternative execution paths when errors occur
- Implement retry strategies for transient failures
- Clean up resources after errors
- Log and report error details
- Structure error recovery workflows

### Error Signaling with Raise

Use the **Raise** task when you need to:

- Signal that an exceptional condition has occurred
- Abort the current execution path when validation fails
- Create custom error types for specific failure scenarios
- Provide detailed error context to upstream error handlers
- Implement business rule validations that may fail

## Try Task Examples

### Basic Error Handling

<code-block lang="yaml" src="workflows/dsl-errors-overview/basic-error-handling.yaml"/>

### Retry Logic for Transient Errors

<code-block lang="yaml" src="workflows/dsl-errors-overview/retry-logic-for-transient-errors.yaml"/>

## Raise Task Examples

### Input Validation

<code-block lang="yaml" src="workflows/dsl-errors-overview/input-validation.yaml"/>

### Business Rule Validation

<code-block lang="yaml" src="workflows/dsl-errors-overview/business-rule-validation.yaml"/>

## Combining Try and Raise

A powerful pattern is combining Try and Raise tasks for comprehensive error handling:

<code-block lang="yaml" src="workflows/dsl-errors-overview/combining-try-and-raise.yaml"/>

The `raise` task rejects an empty order. The `catch` receives that error, or a failure of the payment call, and builds a failure result from it.

Error handling tasks are essential for building robust, production-ready workflows. The Try and Raise tasks work
together to provide comprehensive error management capabilities, allowing you to create workflows that gracefully handle
exceptions, implement recovery strategies, and maintain system reliability even when unexpected conditions occur. 