# Raise

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
  - processValue: # Only executed if value >= 0
    # ...
```

In this example, if the `validateInput` task receives an input object where `.value` is less than 0, it raises the
predefined `invalidInputError`. This raised error
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

## Additional Examples

### Example: Raising Error with Dynamic Detail (Inline)

```yaml
do:
  - processItem:
      # ... some logic ...
      if: "${ .itemStatus == \"FAILED\" }"
      raise:
        error:
          type: "https://myapp.com/errors/processing-failure"
          title: "Item Processing Failed"
          status: 500
          # Construct detail dynamically using workflow context/input
          detail: "Failed to process item ID ${ .itemId }. Error code: ${ $context.errorCodeFromPreviousStep }"
          instance: "${ $task.reference }" # Include task path for context
```

This example shows how to define an error completely inline, using runtime expressions within the `detail` and `instance` fields to provide context-specific information when the error is raised.

### Example: Raising Error to be Caught by Try

```yaml
do:
  - outerTask:
      try:
        - riskyOperation:
            if: "${ .needsSpecialHandling == false }"
            # This error will be caught by the 'catch' block below
            raise:
              error: specialHandlingRequiredError # Defined in workflow.use.errors
        - normalProcessing: # Skipped if error was raised
            # ...
      catch:
        errors:
          with: { type: "https://myapp.com/errors/special-handling" } # Matches the type from specialHandlingRequiredError
        do:
          - handleSpecialCase:
              # ... logic for the special case ...
      then: continueAfterTry
  - continueAfterTry:
      # ... 
```

Here, the `riskyOperation` might raise a specific error. The surrounding `Try` task is configured to `catch` errors of that specific type (`specialHandlingRequiredError` presumably has the type `https://myapp.com/errors/special-handling`). Instead of faulting the workflow, control transfers to the `catch.do` block (`handleSpecialCase`).

## Configuration Options

### `raise` (Object, Required)

This mandatory object defines the error to be raised.

* **`error`** (String | Error, Required): Specifies the error to raise. This can be:
    * A **String**: The name of an [Error definition](dsl-error-handling.md#error-definition-problem-details)
      pre-defined in the `workflow.use.errors` section.
    * An **Inline Error Object**: A complete [Error object](dsl-error-handling.md#error-definition-problem-details)
      defined directly within the `raise` task, specifying `type`, `status`, `title`, `detail`, etc.

## Data Flow

**Note on `Raise` Data Flow**:
*   Standard `input.from` and `input.schema` are processed before the task attempts to raise the error.
*   The resulting `transformedInput` is available if needed by expressions within the `raise.error` definition (though the error definition itself is often static).
*   Crucially, if the `Raise` task executes (i.e., its `if` condition is met or absent), it **never produces an output** and **does not process `output.as` or `export.as` logic**. Its sole purpose upon execution is to interrupt the flow by raising the specified error.

## Flow Control

**Note on `Raise` Flow Control**:
*   The standard `if` condition is evaluated first. If `false`, the `Raise` task is skipped entirely, and its `then` directive is followed as usual.
*   However, if the `if` condition is `true` (or absent), the `Raise` task *will* execute.
*   Upon execution, it **immediately raises the specified error** and transfers control to the [Error Handling](dsl-error-handling.md) mechanism (searching for a suitable `Try` block).
*   Consequently, the `Raise` task's `then` directive is **completely ignored** when the task executes and raises its error.


