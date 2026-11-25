# Try

## Purpose

The `Try` task provides robust error handling and optional retry mechanisms within a workflow. It allows you to attempt
the execution of a block of tasks and define how to react if an error occurs during that execution.

It's primarily used for:

* Gracefully catching specific errors raised by tasks within the `try` block.
* Executing alternative compensation or cleanup tasks when an error is caught (using the `catch.do` block).
* Implementing automatic retries for transient errors (like network timeouts or temporary service unavailability) with
  configurable delays, backoff strategies, and limits.
* Preventing specific errors from halting the entire workflow.

## Basic Usage

Here's a simple example of trying to call an external service and catching a potential communication error:

<code-block lang="yaml" src="workflows/dsl-task-try/try-handle-communication.yaml"/>

If `readStock` fails with a communication error, the catch stores it in `$apiError` and returns a fallback.

The next workflow retries an HTTP 503 using an exponential backoff. The shop can fail its first two calls
so the reader can see the retry recover.

<code-block lang="yaml" src="workflows/dsl-task-try/try-recover-stock.yaml"/>

## Configuration Options

### `try`

The required `try` list contains the tasks to attempt in order. If a task raises an error, the required `catch` block
determines how to handle it.

### Catch Filters

`catch.errors.with` can match any combination of [error properties](dsl-error-handling.md#error-definition-problem-details),
including `type`, `status`, `instance`, `title`, and `detail`. All listed properties must match. When the filter is
omitted, any error can proceed to the `when` and `exceptWhen` checks.

This workflow catches communication errors with status 503. Errors with other statuses propagate to the enclosing scope.

<code-block lang="yaml" src="workflows/dsl-task-try/try-catch-type-and-status.yaml"/>

`catch.as` names the variable that holds the error. It defaults to `error`, available as `$error` in
[runtime expressions](dsl-runtime-expressions.md). The catch proceeds when `when` evaluates to `true` and
`exceptWhen` evaluates to `false`, for whichever expressions are defined.

This workflow uses `when` to catch communication errors with a status of 500 or higher. It binds the error to
`$serviceError`.

<code-block lang="yaml" src="workflows/dsl-task-try/try-use-named-error.yaml"/>

This workflow uses `exceptWhen` to let errors about `customerId` propagate.

<code-block lang="yaml" src="workflows/dsl-task-try/try-exclude-validation-field.yaml"/>

### Retry Policies

Define `catch.retry` as an inline policy or reference a named policy from `use.retries`. A retry policy applies after
the catch filters match.

- The policy's optional `when` and `exceptWhen` expressions decide whether to retry the caught error.
- `delay` sets the base delay. Use an ISO 8601 duration or an object such as `{ seconds: 5 }`.
- `backoff` can be `constant`, `linear`, or `exponential`. Omitting `backoff` uses a constant delay.
- `limit.attempt.count` sets the attempt limit. `limit.attempt.duration` can set a duration limit for retry attempts.
  `limit.duration` limits how long the policy can continue scheduling retries.
- `jitter.from` and `jitter.to` set the range of random time added after calculating the backoff.

Use a two-second delay and an explicit retry count.

<code-block lang="yaml" src="workflows/dsl-task-try/try-fixed-retry.yaml"/>

Combine linear backoff with a two-second base delay, a retry count limit, and a one-minute duration limit.

<code-block lang="yaml" src="workflows/dsl-task-try/try-linear-retry.yaml"/>

Combine exponential backoff with a two-second base delay and jitter between 0.5 and 1 second.

<code-block lang="yaml" src="workflows/dsl-task-try/try-exponential-retry.yaml"/>

Retry only HTTP 503 responses. The fallback reports the status when the policy declines or stops retrying.

<code-block lang="yaml" src="workflows/dsl-task-try/try-conditional-retry.yaml"/>

### Recovery Tasks

`catch.do` runs a list of recovery tasks for an error that matches the catch filters. When a retry policy applies,
the recovery tasks run if the policy stops or declines to schedule another attempt. A successful retry completes
the `Try` task with the retry's output.

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

The final task's transformed output from a successful `try` or `catch.do` block supplies `rawOutput` for `output.as` and
`export.as`.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

Successful execution of `try` or `catch.do` follows the task's `then` directive. Errors outside the catch filters
and errors raised by recovery tasks propagate to the enclosing scope.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f try-handle-communication.yaml
bin/lemline instance start examples try-handle-communication --input '{"status":503,"failures":1,"key":"fallback"}'
bin/lemline definition post -f try-recover-stock.yaml
bin/lemline instance start examples try-recover-stock --input '{"status":503,"failures":2,"key":"try-recover-stock"}'
bin/lemline definition post -f try-catch-type-and-status.yaml
bin/lemline instance start examples try-catch-type-and-status --input '{"status":503,"failures":1,"key":"matching-status"}'
bin/lemline instance start examples try-catch-type-and-status --input '{"status":404,"failures":1,"key":"other-status"}'
bin/lemline definition post -f try-use-named-error.yaml
bin/lemline instance start examples try-use-named-error --input '{"status":503,"failures":1,"key":"named-error"}'
bin/lemline definition post -f try-exclude-validation-field.yaml
bin/lemline instance start examples try-exclude-validation-field --input '{"field":"quantity"}'
bin/lemline instance start examples try-exclude-validation-field --input '{"field":"customerId"}'
bin/lemline definition post -f try-fixed-retry.yaml
bin/lemline instance start examples try-fixed-retry --input '{"status":503,"failures":2,"key":"try-fixed-retry"}'
bin/lemline definition post -f try-linear-retry.yaml
bin/lemline instance start examples try-linear-retry --input '{"status":503,"failures":2,"key":"try-linear-retry"}'
bin/lemline definition post -f try-exponential-retry.yaml
bin/lemline instance start examples try-exponential-retry --input '{"status":503,"failures":2,"key":"try-exponential-retry"}'
bin/lemline definition post -f try-conditional-retry.yaml
bin/lemline instance start examples try-conditional-retry --input '{"status":503,"failures":2,"key":"try-conditional-retry"}'
bin/lemline instance start examples try-conditional-retry --input '{"status":404,"failures":1,"key":"skip-retry"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `try-handle-communication` | `{"status":503,"source":"fallback"}` |
| `try-recover-stock` | The stock for `SKU-1` after two HTTP 503 responses. |
| `try-catch-type-and-status` with `{"status":503,"failures":1,"key":"matching-status"}` | `{"result":"unavailable"}` |
| `try-catch-type-and-status` with `{"status":404,"failures":1,"key":"other-status"}` | Faults with the original HTTP `404` communication error. |
| `try-use-named-error` | `{"reportedStatus":503}` |
| `try-exclude-validation-field` with `{"field":"quantity"}` | `{"result":"rejected"}` |
| `try-exclude-validation-field` with `{"field":"customerId"}` | Faults with the original error for `customerId`. |
| `try-fixed-retry` | The stock for `SKU-1` after two HTTP 503 responses. |
| `try-linear-retry` | The stock for `SKU-1` after two HTTP 503 responses. |
| `try-exponential-retry` | The stock for `SKU-1` after two HTTP 503 responses. |
| `try-conditional-retry` with `{"status":503,"failures":2,"key":"try-conditional-retry"}` | The stock for `SKU-1` after two HTTP 503 responses. |
| `try-conditional-retry` with `{"status":404,"failures":1,"key":"skip-retry"}` | Returns `{"status":404,"retry":false}` after one request. |

Once the workflows finish, query the shop for its counts of failed and successful requests.

```bash
sleep 35
curl -fsS http://127.0.0.1:8787/state
```

> **Lemline**
>
> The attempt count limits retries after the first execution and defaults to one retry when omitted. SQLite checks for due retries periodically, so the interval between requests can exceed the configured delay.
>
> Catch expressions currently receive the error's `detail` field under the key `details`. The example accepts both spellings. This behavior is tracked in [issue #126](https://github.com/lemline/lemline/issues/126).
>
> If the retry policy declines or exhausts retries and `catch.do` is absent, the workflow can complete with the input to `try` as its output. The conditional retry example defines a fallback. This behavior is tracked in [issue #127](https://github.com/lemline/lemline/issues/127).

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
