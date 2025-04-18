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
            call: http  # See [HTTP Call Task](dsl-call-http.md) for details
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

<note title="Summary: Conditions for an Error to be Caught">
For an error raised in the `try` block to be considered "caught" by this `catch` block, it must sequentially pass **all** applicable filters in this order:

1. **Match `errors.with`**: If `errors.with` is defined, the error's properties must exactly match all specified
   criteria.
2. **Pass `when`**: If `when` is defined, its runtime expression (evaluated against the error, accessible via the
   variable named in `as`) must return `true`.
3. **Pass `exceptWhen`**: If `exceptWhen` is defined, its runtime expression (evaluated against the error) must return
   `false`.
4. `retry` is configured and attempts are not exhausted (the `try` task is then retried) OR `catch.do` is defined (the
   `catch.do` is then processed).

</note>

The `catch` block can contain the following properties:

* **`errors`** (Object, Optional): Filters which errors are potentially caught.
    * **`with`**: (Object, Optional) Defines specific properties that an error raised within the `try` block must have
      to be considered for catching by this `catch` block. This allows for fine-grained filtering based on the error's
      characteristics.
        * You can specify one or more standard [Error object](dsl-error-handling.md#error-definition-problem-details)
          fields: `type`, `status`, `instance`, `title`, `details`.
        * **Matching Logic**: An incoming error matches the `with` filter **only if *all* fields specified within
          the `with` object exactly match the corresponding fields in the raised error object.** It acts as a logical
          AND condition.
        * If the `with` object is omitted, this specific property-based filtering step is skipped, and all errors are
          considered potentially catchable (subject to further filtering by `when`/`exceptWhen`).
        * **Example**:
            ```yaml
            catch:
              errors:
                with: 
                  # Only catch errors of this specific type AND status
                  type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
                  status: 503 
                  # instance: /do/0/callApi # Optionally match instance too
              # ... other catch properties (as, when, retry, do) ...
            ```
* **`as`** (String, Optional): Specifies the variable name used to store the caught error object within the scope of the
  `catch` block (`when`, `exceptWhen`, `retry`, `do`). Defaults to `error` (accessible as `$error` in expressions).
* **`when`** (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md) evaluated *if* an error matches the
  `errors.with` filter. The expression has access to the caught error via the variable named by `as`. The error is only
  caught if this expression evaluates to `true`.
    * **Example**: Catch communication errors only if they occurred for a specific instance:
      ```yaml
      catch:
        errors:
          with: { type: ".../communication" }
        as: "commErr" # Optional, using 'commErr' instead of default 'error'
        when: "${ $commErr.instance == '/do/0/unreliableApiCall' }"
        # ... retry or do ...
      ```
* **`exceptWhen`** (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md) evaluated *if* an error
  matches the `errors.with` filter *and* the `when` condition (if present) was true. The expression has access to the
  caught error. The error is *not* caught if this expression evaluates to `true`.
    * **Example**: Catch all validation errors *except* those related to a specific field:
      ```yaml
      catch:
        errors:
          with: { type: ".../validation" }
        # Uses default 'error' variable -> $error
        exceptWhen: "${ $error.details | contains(\"userEmail\" }" # Don't catch if details mention userEmail
        do:
          # Handle other validation errors
      ```
    * **`retry`** (String | Object, Optional): Defines the retry strategy if an error is caught (passes filters). Can be
      a
      string referencing a named `RetryPolicy` defined in `workflow.use.retries`, or an inline `RetryPolicy` object.
        * **`when`** / **`exceptWhen`** (String, Optional): Runtime expressions evaluated *before* calculating the retry
          delay to conditionally decide *if* a retry should occur for the caught error.
        * **`delay`** (String | Object, Required in Policy): Base delay before the first retry (ISO 8601 Duration or
          object
          like `{ seconds: 5 }`).
        * **`backoff`** (Object, Optional): Strategy for increasing delay between retries.
            * `constant: {}`: No increase (default if `backoff` omitted).
            * `linear: {}`: Delay increases linearly (delay * (1 + attemptIndex)).
            * `exponential: {}`: Delay increases exponentially (delay ^ (1 + attemptIndex)).
        * **`limit`** (Object, Optional): Defines limits for retrying.
            * `attempt`: (Object, Optional) Limits related to individual attempts.
                * `count` (Integer, Optional): The maximum number of *total* attempts allowed (the initial attempt plus
                  all
                  retries). If this limit is reached, retrying stops.
                * `duration` (String | Object - Duration, Optional): The maximum allowed duration for any *single*
                  attempt (
                  initial or retry), measured from the start of that specific attempt's execution until its completion (
                  success or error). If any individual attempt exceeds this duration, it's considered a failure (
                  potentially
                  triggering a `Timeout` error or the next retry if attempts remain), even if the overall retry duration
                  limit (`limit.duration`) hasn't been reached. This duration does *not* include the `delay` time
                  preceding
                  the attempt.
            * `duration` (String | Object - Duration, Optional): The maximum *total* duration allowed for the entire
              retry
              process, measured from the start of the *initial* attempt and encompassing all subsequent attempt
              execution
              times *and* the delay periods between them. If this overall duration is exceeded, retrying stops
              immediately,
              even if the attempt count (`limit.attempt.count`) hasn't been reached.
        * **`jitter`** (Object, Optional): Introduces randomness to the retry delay to help prevent simultaneous retries
          from multiple workflow instances causing a "thundering herd" problem on downstream services. Contains the
          following properties:
            * **`from`** (`duration`, Required): The minimum duration value for the random jitter range.
            * **`to`** (`duration`, Required): The maximum duration value for the random jitter range.
            * **How it works**: After the base delay (and any backoff multiplication) is calculated, a random duration
              chosen uniformly from the range [`from`, `to`] (inclusive) is added to it. The actual delay before the
              next
              retry will be `calculated_delay + random(jitter.from, jitter.to)`.
            * **Example**: If the calculated delay (after backoff) is 10 seconds and `jitter` is
              `{ from: { seconds: 1 }, to: { seconds: 3 } }`, the actual delay before the next retry will be a random
              value
              between 11.0 and 13.0 seconds.

      #### Examples

      **1. Simple Retry Count:** Retry up to 3 times with a fixed 5-second delay.

        ```yaml
        retry:
          delay: { seconds: 5 }
          limit:
            attempt:
              count: 4 # 1 initial + 3 retries
        ```

      **2. Linear Backoff with Total Duration Limit:** Retry with delay increasing linearly (2s, 4s, 6s...), stopping
      after a
      total of 1 minute.

        ```yaml
        retry:
          delay: { seconds: 2 }
          backoff:
            linear: { }
          limit:
            duration: { minutes: 1 }
        ```

      **3. Exponential Backoff with Jitter:** Retry with exponentially increasing delay (1s, 2s, 4s...) plus random
      jitter
      between 0.5 and 1.5 seconds.

        ```yaml
        retry:
          delay: { seconds: 1 }
          backoff:
            exponential: { }
          jitter:
            from: { milliseconds: 500 }
            to: { seconds: 1, milliseconds: 500 }
          limit:
            attempt:
              count: 5
        ```

      **4. Conditional Retry:** Only retry if the error details mention a timeout, using the default error variable
      `$error`.

        ```yaml
        retry:
          when: "${ $error.details | contains(\"timeout\") }" # Only retry on timeouts
          delay: { seconds: 10 }
          limit:
            attempt:
              count: 3
        ```
* **`do`** (List<String, Object>, Optional): A block of tasks to execute sequentially *only if* an error is successfully
  caught (passes `errors.with`, `when`, and `exceptWhen` filters).
    * **Output**: If the `catch.do` block executes, its final transformed output becomes the `rawOutput` of the entire
      `Try` task.
    * **Omission**: If `catch.do` is omitted and retries are exhausted, the error propagates upwards.
    <note title="Interaction with Retry">

  If a `retry` policy is also defined for the caught error, the `catch.do` block will **only** execute if **all retry
  attempts are exhausted without success**.

    * If any retry attempt *succeeds*, the `catch.do` block is skipped entirely, the `Try` task completes successfully
      using the output of the successful retry attempt, and execution proceeds via the `Try` task's `then` directive.
    * If all retries *fail* (due to limits or persistent errors), *then* the `catch.do` block (if present) is executed.

    </note>

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note on `Try` Data Flow**:
*   The `rawOutput` used by `output.as` and `export.as` is determined as follows:
    *   If the `try` block completes successfully, `rawOutput` is the `transformedOutput` of the last task within `try`.
    *   If an error is caught and handled by executing the `catch.do` block, `rawOutput` is the `transformedOutput` of the last task within `catch.do`.
    *   If an error is caught, retries are exhausted (or not configured), and there is **no** `catch.do` block, the error propagates upwards. The `Try` task does not produce an output in this case, as it doesn't complete successfully.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note on `Try` Flow Control**:
*   The `Try` task's `then` directive is followed **if and only if** the task completes successfully. Successful completion occurs under these conditions:
    1.  The `try` block finishes without raising any error that is caught by the `catch` filters.
    2.  An error *is* caught, a `retry` policy is triggered, and **any retry attempt succeeds**.
    3.  An error *is* caught, all `retry` attempts (if configured) are exhausted without success, **and** the `catch.do` block (if present) executes successfully.
*   In all other scenarios, the `Try` task does not complete successfully, and its `then` directive is **not** followed. This includes:
    *   An error raised in `try` that is not caught by the `catch` filters.
    *   An error that is caught, exhausts all retries (or has no retry policy), and has **no** `catch.do` block to handle it (the error propagates).
    *   The `catch.do` block itself raises an error.


