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

Read a product and return a fallback if the shop reports HTTP 404.

<code-block lang="yaml" src="workflows/dsl-errors-overview/basic-error-handling.yaml"/>

### Retry Logic for Transient Errors

Retry a temporary inventory failure and return cached data if retries are exhausted.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

## Raise Task Examples

### Input Validation

Reject a quantity below one with a specific error.

<code-block lang="yaml" src="workflows/dsl-errors-overview/input-validation.yaml"/>

### Business Rule Validation

Compare the requested quantity with the shop’s current stock.

<code-block lang="yaml" src="workflows/dsl-errors-overview/business-rule-validation.yaml"/>

## Combining Try and Raise

A powerful pattern is combining Try and Raise tasks for comprehensive error handling:

<code-block lang="yaml" src="workflows/dsl-errors-overview/combining-try-and-raise.yaml"/>

The `raise` task rejects an empty order. The same `catch` receives that error or a failure of the reservation or payment call.

Error handling tasks are essential for building robust, production-ready workflows. The Try and Raise tasks work
together to provide comprehensive error management capabilities, allowing you to create workflows that gracefully handle
exceptions, implement recovery strategies, and maintain system reliability even when unexpected conditions occur.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f basic-error-handling.yaml
bin/lemline instance start examples basic-error-handling --input '{"productId":"SKU-1"}'
bin/lemline instance start examples basic-error-handling --input '{"productId":"SKU-9"}'
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":2}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
bin/lemline definition post -f input-validation.yaml
bin/lemline instance start examples input-validation --input '{"quantity":2}'
bin/lemline instance start examples input-validation --input '{"quantity":0}'
bin/lemline definition post -f business-rule-validation.yaml
bin/lemline instance start examples business-rule-validation --input '{"quantity":2}'
bin/lemline instance start examples business-rule-validation --input '{"quantity":120}'
bin/lemline definition post -f combining-try-and-raise.yaml
bin/lemline instance start examples combining-try-and-raise --input '{"orderId":"O-140","items":[]}'
bin/lemline instance start examples combining-try-and-raise --input '{"orderId":"O-141","items":[{"id":"SKU-1","quantity":1}],"paymentToken":"declined"}'
bin/lemline instance start examples combining-try-and-raise --input '{"orderId":"O-142","items":[{"id":"SKU-1","quantity":1}],"paymentToken":"approved"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `basic-error-handling` with `{"productId":"SKU-1"}` | `{"listed":true,"productId":"SKU-1"}` |
| `basic-error-handling` with `{"productId":"SKU-9"}` | `{"listed":false}` |
| `retry-and-fallback` with `{"failures":2}` | Reads the stock after two HTTP 503 responses. |
| `retry-and-fallback` with `{"failures":5}` | `{"inventory":[],"stale":true}` |
| `input-validation` with `{"quantity":2}` | `{"accepted":true}` |
| `input-validation` with `{"quantity":0}` | Faults with `Quantity must be positive`. |
| `business-rule-validation` with `{"quantity":2}` | `{"accepted":true}` |
| `business-rule-validation` with `{"quantity":120}` | Faults with `Insufficient stock`. |
| `combining-try-and-raise` with `{"orderId":"O-140","items":[]}` | `{"paid":false,"status":400}` |
| `combining-try-and-raise` with `{"orderId":"O-141","items":[{"id":"SKU-1","quantity":1}],"paymentToken":"declined"}` | `{"paid":false,"status":402}` |
| `combining-try-and-raise` with `{"orderId":"O-142","items":[{"id":"SKU-1","quantity":1}],"paymentToken":"approved"}` | `{"paid":true}` |

Allow the retrying instances to finish before stopping the shop. In SQLite this can take up to about a minute.

```bash
sleep 60
```

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
