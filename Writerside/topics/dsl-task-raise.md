# Raise Task (`raise`)

## Purpose

The `Raise` task is used to explicitly signal an error condition within the workflow.
It constructs and throws a specific [Workflow Error](dsl-error-handling.md) object,
which then interrupts the normal flow and triggers the error handling mechanism
(typically caught by an enclosing [`Try`](dsl-task-try.md) task).

It's primarily used for:

* Signaling business-level errors or exceptional conditions discovered during workflow logic.
* Manually triggering fault handling paths.
* Converting non-standard error conditions into standardized workflow errors.
* Terminating a specific execution path due to an unrecoverable state.

## Basic Usage

Here's an example of raising a custom error based on input data:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: raise-basic
  version: '1.0.0'
use:
  errors:
    # Define a reusable error type
    invalidInputError:
      type: https://mycompany.com/errors/invalid-input
      title: "Invalid Input Received"
      status: 400 # Bad Request
do:
  - validateInput:
      if: "${ .value < 0 }" # Check if input value is negative
      raise:
        error: invalidInputError # Reference the defined error
        # Optionally add dynamic details
        with:
          details: "Negative value not allowed: ${ .value }"
  - processValue: # Only executed if value >= 0
    # ...
```

In this example, if the `validateInput` task receives an input object where `.value` is less than 0, it raises the
predefined `invalidInputError`. The `details` field is dynamically constructed using the input value. This raised error
would then typically be caught by a surrounding `Try` task.

You can also define the error inline:

```yaml
do:
  - checkInventory:
      # ... logic to check stock ...
      if: "${ .stockCount < .requestedAmount }"
      raise:
        # Define the error directly within the raise task
        error:
          type: https://mycompany.com/errors/out-of-stock
          title: "Insufficient Stock"
          status: 409 # Conflict
          detail: "Requested ${ .requestedAmount }, but only ${ .stockCount } available."
```

## Configuration Options

### `raise` (Object, Required)

This mandatory object defines the error to be raised.

* **`error`** (String | Error, Required): Specifies the error to raise. This can be:
    * A **String**: The name of an [Error definition](dsl-error-handling.md#error-definition-problem-details)
      pre-defined in the `workflow.use.errors` section.
    * An **Inline Error Object**: A complete [Error object](dsl-error-handling.md#error-definition-problem-details)
      defined directly within the `raise` task, specifying `type`, `status`, `title`, `detail`, etc.
* **`with`** (Object, Optional): An object containing properties that override or add to the fields of the referenced or
  inline error definition. This is commonly used to add dynamic `detail` information based on the current workflow
  state.
    * The properties within `with` (e.g., `title`, `detail`, `status`, `instance`) will overwrite the corresponding
      fields from the base error definition before the error is raised.

```yaml
raise:
  error: myBaseError # Reference error defined in workflow.use.errors
  with:
    # Override or add details dynamically
    detail: "Operation failed for ID: ${ .itemId }. Context: ${ $context }"
    instance: "${ $task.reference }" # Set instance to the current task path
```

### Input/Output Handling

The `Raise` task fundamentally interrupts the normal data flow.

* **`input.schema` / `input.from`**: Standard validation and transformation. This transformed input is available for use
  in expressions within `raise.with`.
* **Output**: A `Raise` task **does not produce an output** in the normal sense. Its execution results in a
  [error](dsl-error-handling.md#error-definition-problem-details) being thrown, which bypasses standard output
  processing (`output.as`, `export.as`).
* **Context (`export.as`)**: Because the task faults, it does not reach the stage where `export.as` would normally be
  evaluated.

### Conditional Execution `if` (String, Optional)

Standard conditional execution. The `Raise` task only executes (and thus raises the error) if the `if` expression
evaluates to `true`.

### Flow Control (`then`)

* **Type**: `string | FlowDirectiveEnum`
* **Required**: No

The `then` directive is **ignored** if the `Raise` task executes, because raising an error immediately transfers control
to the error handling mechanism (searching for a `Try` task). The `then` directive *would* apply only if the `Raise`
task was skipped due to its `if` condition evaluating to `false`.

### Data Flow

The `Raise` task does not produce output or perform export operations, as it interrupts the flow by design.

### Flow Control

The `Raise` task ignores its `then` directive when it executes (as it transfers control to error handling).