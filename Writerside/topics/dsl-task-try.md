# Try Task (`try`)

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

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: try-catch-basic
  version: '1.0.0'
do:
  - attemptServiceCall:
      try:
        # Tasks to attempt inside the 'try' block (must be a list)
        - callMyApi:
            call: http
            with:
              method: get
              uri: https://api.unreliable.com/data
            # This call might fail with a communication error
      catch:
        # Define how to handle errors caught from the 'try' block
        errors:
          # Catch specific error types (optional)
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        as: "apiError" # Store the caught error details in $apiError variable (default is $error)
        do:
          # Tasks to execute ONLY if an error matching the filter is caught
          - logFailure:
              call: log
              with:
                level: "warn"
                message: "API call failed. Error: ${ $apiError }"
          - setDefaultValue:
              set: # Provide a default output if the try block failed
                data: null
                status: "failed"
  - continueProcessing: # Executes after attemptServiceCall (either success or caught error)
    # Input is the output of 'callMyApi' if successful,
    # or the output of 'setDefaultValue' if an error was caught.
    # ...
```

In this example, if `callMyApi` fails with a standard `communication` error, the `catch` block is activated. The error
details are stored in the `$apiError` variable (accessible within the `catch.do` block), the failure is logged, and a
default output is set. Execution then proceeds normally to `continueProcessing`. If `callMyApi` succeeds, the `catch`
block is skipped entirely.

Here's an example demonstrating automatic retries:

```yaml
do:
  - attemptWithRetry:
      try:
        - fetchCrucialData:
            call: http
            with: # ... service details ...
            # Might temporarily fail with 503
      catch:
        errors:
          with:
            status: 503 # Only catch 'Service Unavailable' errors
        retry:
          delay: PT2S    # Initial delay 2 seconds
          backoff:
            exponential: { } # Exponential backoff (2s, 4s, 8s...)
          limit:
            attempt:
              count: 4   # Max 1 initial attempt + 3 retries = 4 total
        # No 'catch.do' means if all retries fail, the error is re-thrown
  - processData: # Only reached if fetchCrucialData succeeds eventually
    # ...
```

This example attempts `fetchCrucialData`. If it fails with a 503 status, it waits 2 seconds and retries. If it fails
again, it waits 4 seconds, then 8 seconds. If it still fails after the 4th total attempt, the 503 error is *not*
caught (because there's no `catch.do`), and the workflow likely faults unless a parent `Try` catches it.

## Configuration Options

### `try` (List<String, Object>, Required)

This mandatory property contains a list defining the sequence of tasks to be attempted.

If any task within this block raises an error, the runtime checks the corresponding `catch` block to see if the error
should be handled.

### `catch` (Object, Required)

This mandatory object defines how errors originating from the `try` block are handled.

* **`errors`** (Object, Optional): Filters which errors are potentially caught.
    * **`with`**: An object specifying criteria to match against the
      raised [Error object](dsl-error-handling.md#error-definition-problem-details). Errors only match if *all*
      specified fields ( `type`, `status`, `instance`, `title`, `details`) match the corresponding fields in the raised
      error. If `errors.with` is omitted, all errors are potentially caught (subject to `when`/`exceptWhen`).
* **`as`** (String, Optional): Specifies the variable name used to store the caught error object within the scope of the
  `catch` block (`when`, `exceptWhen`, `retry`, `do`). Defaults to `error` (accessible as `$error` in expressions).
* **`when`** (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md) evaluated *if* an error matches the
  `errors.with` filter. The expression has access to the caught error via the variable named by `as`. The error is only
  caught if this expression evaluates to `true`.
* **`exceptWhen`** (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md) evaluated *if* an error
  matches the `errors.with` filter *and* the `when` condition (if present) was true. The expression has access to the
  caught error. The error is *not* caught if this expression evaluates to `true`.
* **`do`** (List<String, Object>, Optional): A block of tasks to execute sequentially *only if* an error is successfully
  caught (passes `errors.with`, `when`, and `exceptWhen` filters, and is not retried or retries have been exhausted). If
  this block executes, its output becomes the output of the `Try` task. If omitted and an error is caught and not
  retried, the `Try` task effectively produces no output, and execution proceeds based on its `then` directive.
* **`retry`** (String | Object, Optional): Defines the retry strategy if an error is caught (passes filters). Can be a
  string referencing a named `RetryPolicy` defined in `workflow.use.retries`, or an inline `RetryPolicy` object.
    * **`delay`** (String | Object, Required in Policy): Base delay before the first retry (ISO 8601 Duration or object
      like `{ seconds: 5 }`).
    * **`backoff`** (Object, Optional): Strategy for increasing delay between retries.
        * `constant: {}`: No increase (default if `backoff` omitted).
        * `linear: {}`: Delay increases linearly (delay * (1 + attemptIndex)).
        * `exponential: {}`: Delay increases exponentially (delay ^ (1 + attemptIndex)).
    * **`limit`** (Object, Optional): Defines limits for retrying.
        * `attempt`: Max number of *total* attempts (initial + retries) or max duration for a single attempt.
        * `duration`: Max total duration for all attempts and delays.
    * **`jitter`** (String | Object, Optional): Adds a random duration (up to the specified amount) to each retry delay.
    * **`when`** / **`exceptWhen`** (String, Optional): Runtime expressions evaluated *before* calculating the retry
      delay to conditionally decide *if* a retry should occur for the caught error.

```yaml
catch:
  errors:
    with: { type: ".../communication", status: 503 }
  as: "commError"
  when: "${ $commError.instance == '/do/0/callMyApi' }" # Only catch if from specific task
  retry: myStandardRetryPolicy # Reference named policy
  do:
    - logRetryFailure:
        # Executes if retries are exhausted
        call: log
        with:
          message: "API failed after retries: ${ $commError.title }"
    - setFallback:
        set: { result: "fallback" }
```

### Input/Output Handling

The `Try` task interacts with [Data Flow](dsl-data-flow.md) in specific ways:

* **`input.schema` / `input.from`**: Standard validation/transformation applied *before* the `try` block is entered.
* **Output**: The output depends on the outcome:
    * **Success**: If all tasks in the `try` block complete without error, the *raw output* of the `Try` task is the
      *transformed output* of the last task in the `try` block.
    * **Error Caught & `catch.do` Executes**: If an error is caught and the optional `catch.do` block executes, the *raw
      output* of the `Try` task is the *transformed output* of the last task in the `catch.do` block.
    * **Error Caught & No `catch.do`**: If an error is caught (and retries are exhausted or not configured) but there is
      no `catch.do` block, the `Try` task effectively completes successfully but produces *no specific output* (often
      treated as `null` or the original input depending on the runtime, but it doesn't pass forward the output of the
      failed task). Execution continues based on the `Try` task's `then` directive.
    * **Error Not Caught**: If an error occurs in the `try` block that is *not* caught by the `catch` filters (or
      `retry` conditions), the error propagates up, and the workflow faults unless caught by a parent `Try`.
* **`output.as` / `output.schema`**: Applied to the determined raw output (from success path or `catch.do` path) *after*
  the `try` or `catch.do` block completes.
* **`export.as` / `export.schema`**: Standard context update mechanism, operating after `output.as`.

### Conditional Execution (`if`)

* **Type**: `string` (Runtime Expression)
* **Required**: No

Standard conditional execution applied to the `Try` task *as a whole*. If the `if` expression evaluates to `false`, the
entire `Try` task (including attempting the `try` block and any potential error handling) is skipped.

### Flow Control (`then`)

* **Type**: `string | FlowDirectiveEnum`
* **Required**: No

Standard flow control. Defines where execution proceeds *after* the `Try` task completes successfully, either because:

* The `try` block finished without error.
* An error was caught and handled (either by executing `catch.do` or by simply catching it without a `do` block when
  retries were exhausted).

### Data Flow and Flow Control

The `Try` task interacts with standard [Data Flow Management](dsl-data-flow.md) (`input`, `output`, `export`, schemas)
and [Flow Control](dsl-flow-control.md) (`if`, `then`) concepts, but with specific behaviors related to error handling:

* **Input**: `input.from` and `input.schema` are applied *before* the `try` block is entered.
* **Conditional Execution (`if`)**: If the `Try` task's `if` condition is false, the entire task (including the `try`
  and `catch` blocks) is skipped, and flow proceeds based on its `then` directive.
* **Output Determination**: The output depends heavily on the execution path:
    * **Success Path**: If the `try` block completes without error, the `rawOutput` is the transformed output of the
      last task in the `try` block.
    * **Error Caught Path (with `catch.do`)**: If an error is caught and handled by executing the `catch.do` block, the
      `rawOutput` is the transformed output of the last task in the `catch.do` block.
    * **Error Caught Path (no `catch.do`)**: If an error is caught (and retries fail or aren't configured) but there is
      no `catch.do`, the task completes successfully but provides *no specific output* from the failed path.
* **Output Processing**: Standard `output.as` and `output.schema` are applied to the `rawOutput` determined by the
  successful path (either `try` or `catch.do`).
* **Context Export**: Standard `export.as` and `export.schema` operate on the processed output.
* **Flow Control (`then`)**: The `Try` task's `then` directive is followed *only if* the task completes successfully (
  either the `try` block succeeded or an error was caught and handled by `catch.do` or by just being caught without a
  `do` block). If an uncaught error propagates, the `then` directive is ignored.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Try` task's output (`rawOutput` feeding into `output.as`/`export.as`) depends on the execution path:
 *   **Success**: Output is from the last task in the `try` block.
 *   **Error Caught (with `catch.do`)**: Output is from the last task in the `catch.do` block.
 *   **Error Caught (no `catch.do`)**: No specific output is passed forward from the failed path.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: The `Try` task's `then` directive is only followed if the `try` block succeeds OR if an error is caught and handled (either by `catch.do` or simply by being caught without a `do` block after exhausting retries). Uncaught errors prevent `then` from being followed.
