# Lemline Error Handling Examples

This document provides examples of error handling and resilience patterns in Lemline workflows. Robust error handling is essential for building reliable workflows that can gracefully recover from failures when interacting with external systems, processing data, or executing business logic.

> **Lemline runtime:** a failed HTTP call raises a `runtime` error with status 500, whatever the HTTP status. The HTTP status is the start of the error title, for example `HTTP 404: ...`. The examples on this page therefore match HTTP failures on the title of the caught error. See [Error Reference](lemline-ref-errors.md).

## Run the Examples

Each example below is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker
running. Run the commands from a second terminal in the setup directory. With the JAR, substitute
`java -jar lemline.jar` for `bin/lemline`. The examples call the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop), whose `fail`, `failTimes` and `delayMs`
query parameters produce the failures and the slow answer. Start it from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The worker picks up delayed retries every 10 seconds, so allow a minute or two for the retry examples.

## Basic Try-Catch Pattern

The fundamental error handling mechanism in Open Workflow DSL is the try-catch block, which allows workflows to catch and handle exceptions.

### Simple Try-Catch Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/simple-try-catch-example.yaml"/>

Save it as `simple-try-catch-example.yaml`:

```bash
bin/lemline definition post -f simple-try-catch-example.yaml
bin/lemline instance start examples catch-missing-product --input '{"productId":"SKU-1"}'
bin/lemline instance start examples catch-missing-product --input '{"productId":"SKU-404"}'
bin/lemline instance start examples catch-missing-product --input '{"productId":"SKU-1","failStatus":500}'
```

The first instance returns the garden hose. For `SKU-404` the shop answers HTTP 404: the error is caught and the workflow completes with the task input, `{"productId":"SKU-404"}`. The HTTP 500 does not match the condition: the instance faults with a `runtime` error titled `HTTP 500`.

This example demonstrates:
- Wrapping a potentially failing HTTP call in a `try` block
- Catching only the HTTP 404 response, with a `when` condition on the error title
- Building the request URI from the `productId` workflow input
- The workflow continues after the error is caught

Key aspects of basic try-catch:
- Execution continues after the catch block
- If an error occurs that doesn't match the `errors` criteria, it will propagate up
- If no error occurs, the `catch` block is skipped

## Error Handling with Custom Actions

When an error occurs, you often need to perform specific actions in response, such as logging, notification, or fallback behavior.

### Try-Catch with Custom Error Handling

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/try-catch-with-custom-error-handling.yaml"/>

Save it as `try-catch-with-custom-error-handling.yaml`:

```bash
bin/lemline definition post -f try-catch-with-custom-error-handling.yaml
bin/lemline instance start examples reserve-known-product --input '{"productId":"SKU-1","orderId":"ORD-1201"}'
bin/lemline instance start examples reserve-known-product --input '{"productId":"SKU-404","orderId":"ORD-1202"}'
```

For SKU-1, the product is found and one unit is reserved for `ORD-1201`. For SKU-404, the catch emits `com.example.product.not-found` and stores the error in the context, so `reserveProduct` is skipped: the shop has no reservation for `ORD-1202`.

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

Save it as `inline-retry-configuration.yaml`:

```bash
bin/lemline definition post -f inline-retry-configuration.yaml
bin/lemline instance start examples retry-inline --input '{"productId":"SKU-2","failures":2}'
```

The shop answers HTTP 503 twice, then returns the watering can after two retries.

This example demonstrates:
- Retrying a failed operation only for an HTTP 503 response (Service Unavailable)
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

Save it as `reusable-retry-configuration.yaml`:

```bash
bin/lemline definition post -f reusable-retry-configuration.yaml
bin/lemline instance start examples retry-reusable --input '{"productId":"SKU-2","failures":2}'
```

Same result as the inline policy: two retries, then the watering can.

This example demonstrates:
- Defining a reusable retry policy named `default`
- Referencing the reusable policy in catch blocks
- Maintaining consistent retry behavior across the workflow

## Conditional Retry

Sometimes you want to retry only under specific conditions, rather than for all errors of a certain type.

### Conditional Retry Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/conditional-retry-example.yaml"/>

Save it as `conditional-retry-example.yaml`:

```bash
bin/lemline definition post -f conditional-retry-example.yaml
bin/lemline instance start examples conditional-retry --input '{"failStatus":503}'
bin/lemline instance start examples conditional-retry --input '{"failStatus":429}'
bin/lemline instance start examples conditional-retry --input '{"failStatus":400}'
```

Each input makes the shop fail once. After a 503 or a 429, the call is retried and the workflow returns the stock. After a 400, `when` is false: there is no retry, the error still matches the `catch`, so it counts as handled and the workflow completes with its input `{"failStatus":400}`. The shop's request list shows two calls for each of the first two instances and one for the third.

This example demonstrates:
- Capturing the error in a variable using `as: stockError`
- Using a condition with `when` to determine if retry should occur
- Only retrying for server errors (5xx) or rate limiting (429)
- Customizing the retry policy for these specific conditions

## Error Type Hierarchies

The DSL supports hierarchical error types, allowing for more flexible error matching.

### Error Type Hierarchy Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/error-type-hierarchy-example.yaml"/>

Save it as `error-type-hierarchy-example.yaml`:

```bash
bin/lemline definition post -f error-type-hierarchy-example.yaml
bin/lemline instance start examples error-hierarchy --input '{"email":"ada@example.com"}'
bin/lemline instance start examples error-hierarchy --input '{"email":"not-an-email"}'
bin/lemline instance start examples error-hierarchy --input '{"email":"ada@example.com","serviceDown":true}'
```

A valid email completes with the shop's answer. An invalid one raises the typed error, which the `switch` routes to `handleValidation`: `errorType` is `validation`. When the shop answers HTTP 503, the error is a `runtime` error outside `https://example.com/errors`: the `catch` ignores it and the instance faults.

This example demonstrates:
- A task that raises a typed error when the shop reports an invalid email
- A single `catch` that only accepts errors under `https://example.com/errors`
- Hierarchical error matching with a `switch` on the caught error's type (most specific to most generic)
- Different handling for each error category
- Switch cases are evaluated in order, and the first matching case handles the error

## Compensating Transactions

In some workflows, you need to perform cleanup actions when an error occurs after some operations have already succeeded.

### Compensating Transaction Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/compensating-transaction-example.yaml"/>

Save it as `compensating-transaction-example.yaml`:

```bash
bin/lemline definition post -f compensating-transaction-example.yaml
bin/lemline instance start examples compensating-transaction --input '{"orderId":"ORD-1203","customerId":"C-100","orderItems":[{"id":"SKU-1","quantity":2}],"paymentDetails":{"token":"approved"}}'
bin/lemline instance start examples compensating-transaction --input '{"orderId":"ORD-1204","customerId":"C-100","orderItems":[{"id":"SKU-1","quantity":2}],"paymentDetails":{"token":"declined"}}'
```

ORD-1203 is reserved and paid. For ORD-1204 the charge answers HTTP 402: the catch releases the reservation and leaves `Payment failed: HTTP 402: …` in the inbox of `C-100`. The shop's stock goes back up by 2.

This example demonstrates:
- A sequence of operations (inventory reservation, payment processing)
- Capturing the output of the first operation for later reference
- When a call in the `try` block fails, executing compensating actions to clean up
- Canceling the inventory reservation to maintain consistency
- Notifying the customer about the failure

## Circuit Breaker Pattern

The circuit breaker pattern prevents repeated calls to a failing service, reducing load and allowing it to recover.

### Circuit Breaker Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/circuit-breaker-example.yaml"/>

Save it as `circuit-breaker-example.yaml`:

```bash
bin/lemline definition post -f circuit-breaker-example.yaml
bin/lemline instance start examples circuit-breaker
```

The shop answers HTTP 503 every time. The workflow calls it four times (the call and three retries), then stops calling: the catch has no `do` list, so the error counts as handled and the instance completes with its input.

This example demonstrates:
- A reusable retry policy named `circuitBreaker` that bounds the calls to a failing service
- Exponential backoff starting at 1 second, so each retry waits longer than the previous one
- `limit.attempt.count`: at most 3 retries, after which the workflow stops calling the service
- The DSL has no dedicated circuit breaker settings (failure threshold, recovery time): the retry limit is what stops the calls

## Timeout Handling

Timeouts prevent workflows from hanging indefinitely when external systems are unresponsive.

### Timeout Example

<code-block lang="yaml" src="workflows/lemline-examples-error-handling/timeout-example.yaml"/>

Save it as `timeout-example.yaml`:

```bash
bin/lemline definition post -f timeout-example.yaml
bin/lemline instance start examples timeout-handling
```

The shop answers after 5 seconds. The 2-second task timeout is not applied: the call waits for the answer, the instance completes with the stock, and the catch branch does not run.

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request. The `catch` branch of this example therefore does not run on Lemline yet.

This example shows how a timeout would be declared and caught:
- Setting a timeout for an HTTP call
- Catching the specific timeout error type
- Custom handling for timeout conditions

On Lemline, use a `listen` timeout, as in [How to cancel, resume, or suspend a workflow](lemline-howto-lifecycle.md#bounding-how-long-a-workflow-waits), to bound a wait.

## Comprehensive Error Handling Strategy

Several of these patterns combine in a single `try` task:

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

Save it as `retry-and-fallback.yaml`:

```bash
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
```

The call and its three retries fail; the workflow returns `{"inventory":[],"stale":true}`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

This example demonstrates:
- A reusable retry definition declared in `use.retries` and referenced by name
- Catching only the HTTP 503 response
- Retrying with exponential backoff, up to the attempt limit
- A fallback result set in `catch.do` once the retries are exhausted

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