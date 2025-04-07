# Error Handling

## Purpose

Failures and exceptional conditions are inevitable in any complex system. The Serverless Workflow DSL provides robust
mechanisms to define, raise, identify, and handle errors gracefully. This ensures workflows can recover from anticipated
issues, perform compensation logic, implement retries for transient problems, and avoid abrupt termination for
manageable faults.

## Error Definition (Problem Details)

Errors in Serverless Workflow are structured based on
the [RFC 7807 Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc7807). This provides a
standardized way to communicate error information.

The core fields of a workflow error object are:

* **`type`** (String, Required): A URI reference that identifies the specific type of error. The specification defines
  several [Standard Error Types](#standard-error-types).
* **`status`** (Integer, Required): The status code associated with this occurrence of the error (often analogous to
  HTTP status codes).
* **`title`** (String, Optional): A short, human-readable summary of the error.
* **`detail`** (String, Optional): A human-readable explanation specific to this occurrence of the error.
* **`instance`** (String, Required): A URI reference that identifies the specific occurrence of the problem. In
  Serverless Workflow, this is typically a [JSON Pointer](https://datatracker.ietf.org/doc/html/rfc6901) indicating the
  specific task or component within the workflow definition where the error originated (e.g., `/do/1/callApi`).

See `com.lemline.sw.errors.WorkflowError.kt` for the corresponding data class.

## Standard Error Types

The specification defines standard error `type` URIs for common issues. Runtimes **must** use these types when
applicable to ensure consistent behavior. Authors should use these when defining custom errors for similar conditions.

Base URI: `https://serverlessworkflow.io/spec/1.0.0/errors/`

| Type Suffix      | Default Status | Description                                                                 | Implementation (`WorkflowErrorType`) |
| :--------------- | :------------- | :-------------------------------------------------------------------------- | :----------------------------------- |
| `configuration`  | 400            | Problem with the workflow definition itself (e.g., invalid `then` target).   | `CONFIGURATION`                      |
| `validation`     | 400            | Input/output data failed schema validation (`input.schema`, `output.schema`). | `VALIDATION`                         |
| `expression`     | 400            | Evaluation of a runtime expression failed (e.g., syntax error, bad access). | `EXPRESSION`                         |
| `authentication` | 401            | Authentication failed when accessing a protected resource.                  | `AUTHENTICATION`                     |
| `authorization`  | 403            | Insufficient permissions to access a resource (e.g., secrets).               | `AUTHORIZATION`                      |
| `timeout`        | 408            | A configured workflow or task timeout was exceeded.                         | `TIMEOUT`                            |
| `communication`  | 500            | Error during communication with an external service (e.g., HTTP call fail). | `COMMUNICATION`                      |
| `runtime`        | 500            | General runtime error not covered by other types.                         | `RUNTIME`                            |

## Raising Errors

Errors can enter the system in two main ways:

1. **Implicitly by the Runtime**: The workflow engine raises standard errors automatically when certain conditions
   occur:
    * Expression evaluation fails (`expression` error).
    * Schema validation fails (`validation` error).
    * A task or workflow times out (`timeout` error).
    * A `call` task fails due to network issues (`communication` error).
    * Authentication/Authorization issues arise (`authentication`/`authorization` errors).
    * Workflow definition issues are detected (`configuration` error).
2. **Explicitly using the `Raise` Task**: Authors can use the [`Raise` task](dsl-task-raise.md) to explicitly throw a
   specific error (either predefined in `use.errors` or defined inline) based on workflow logic.
   ```yaml
   - checkInventory:
       # ... check logic ...
       if: "${ .stock < .needed }"
       raise:
         error: outOfStockError # Defined in use.errors
         with:
           detail: "Needed ${ .needed }, only ${ .stock } available."
   ```

## Catching and Handling Errors (`Try...Catch`)

The primary mechanism for handling errors is the [`Try` task](dsl-task-try.md).

```yaml
- myTryTask:
    try:
      # Block of tasks to attempt
      - taskToTry:
        # ... might raise an error ...
    catch:
      # Block defining how to handle errors from 'try'
      errors: { with: { type: ".../specificErrorType" } } # 1. Filter which errors to catch
      when: "${ $error.instance == '/do/0/taskToTry' }"   # 2. Conditional catch
      as: myError                                       # 3. Store error object
      retry: myRetryPolicy                              # 4. Optionally retry
      do: # 5. Execute compensation logic
        - logError: # ... uses $myError ...
        - setFallback: # ...
```

1. **Filtering (`catch.errors.with`)**: You can specify criteria (`type`, `status`, etc.) to only catch specific errors.
   If omitted, all errors are candidates for catching (subject to `when`/`exceptWhen`).
2. **Conditional Catch (`catch.when`, `catch.exceptWhen`)**: Further refine catching logic using runtime expressions
   evaluated against the error object (available via `catch.as` variable).
3. **Error Access (`catch.as`)**: The caught error object is made available within the `catch` block scope (for `when`,
   `exceptWhen`, `retry`, `do`) under the specified variable name (default `$error`).
4. **Retries (`catch.retry`)**: If an error is caught, a retry policy (inline or referenced) can be defined to
   re-execute the *entire* `try` block after a calculated delay. Retries have limits and optional backoff strategies.
   See [`Try` task documentation](dsl-task-try.md) for details.
5. **Compensation (`catch.do`)**: If an error is caught and not retried (or retries are exhausted), the optional
   `catch.do` block is executed. This allows for cleanup, logging, or setting fallback values. The output of this block
   becomes the output of the `Try` task.

## Error Propagation

If an error is raised (explicitly or implicitly) and:

* It occurs outside any `Try` task, OR
* It occurs within a `try` block, but is *not* matched by the corresponding `catch` filters (`errors.with`, `when`,
  `exceptWhen`), OR
* It is caught, but retries are exhausted, and there is no `catch.do` block to handle it gracefully...

then the error **propagates upwards**. The runtime searches the parent scope for another enclosing `Try` task. If an
outer `Try` task catches the error, its handling logic applies. If the error propagates all the way to the top level of
the workflow without being caught, the entire workflow execution **faults** and terminates unsuccessfully, reporting the
uncaught error. 