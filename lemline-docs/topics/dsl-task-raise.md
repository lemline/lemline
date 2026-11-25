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

<code-block lang="yaml" src="workflows/dsl-task-raise/raise-basic.yaml"/>

In this example, if the `validateInput` task receives an input object where `.value` is less than 0, it raises the
predefined `invalidInputError`. This raised error
would then typically be caught by a surrounding `Try` task.

You can also define the error inline:

<code-block lang="yaml" src="workflows/dsl-task-raise/raise-inline-stock-error.yaml"/>

## Additional Examples

### Example: Raising Error with Dynamic Detail (Inline)

<code-block lang="yaml" src="workflows/reference/raise-order-error.yaml"/>

This example shows how to define an error completely inline, using a runtime expression within the `detail` field to provide context-specific information when the error is raised.

### Example: Raising Error to be Caught by Try

<code-block lang="yaml" src="workflows/dsl-task-raise/raise-and-catch.yaml"/>

With `needsSpecialHandling: true`, the error type matches the catch filter and control passes to `handleSpecialCase`. Otherwise, `normalProcessing` runs.

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

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f raise-basic.yaml
bin/lemline instance start examples raise-basic --input '{"value":1}'
bin/lemline instance start examples raise-basic --input '{"value":-1}'
bin/lemline definition post -f raise-inline-stock-error.yaml
bin/lemline instance start examples raise-inline-stock-error --input '{"stockCount":5,"requestedAmount":3}'
bin/lemline instance start examples raise-inline-stock-error --input '{"stockCount":1,"requestedAmount":3}'
bin/lemline definition post -f raise-order-error.yaml
bin/lemline instance start examples raise-order-error --input '{"stock":1,"quantity":3}'
bin/lemline definition post -f raise-and-catch.yaml
bin/lemline instance start examples raise-and-catch --input '{"needsSpecialHandling":true}'
bin/lemline instance start examples raise-and-catch --input '{"needsSpecialHandling":false}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `raise-basic` with `{"value":1}` | `{"processed":true}` |
| `raise-basic` with `{"value":-1}` | The instance faults with status `400` and title `Invalid Input Received`. |
| `raise-inline-stock-error` with `{"stockCount":5,"requestedAmount":3}` | `{"accepted":true}` |
| `raise-inline-stock-error` with `{"stockCount":1,"requestedAmount":3}` | The instance faults with status `409` and title `Insufficient stock`. |
| `raise-order-error` | The instance faults with status `409` and detail `Requested 3; available 1`. |
| `raise-and-catch` with `{"needsSpecialHandling":true}` | `{"route":"manual"}` |
| `raise-and-catch` with `{"needsSpecialHandling":false}` | `{"route":"automatic"}` |
