# Runtime Expressions

## Purpose

Runtime expressions are dynamic elements within the Open Workflow DSL that enable flexible and adaptable workflow
behaviors. They provide a powerful way to:

* Access and manipulate data during workflow execution (e.g., input data, context data, task outputs).
* Evaluate conditions for tasks like `if`, `while` (in `For` loops), and `when` (in `Switch` and `Try` tasks).
* Transform data structures using `input.from`, `output.as`, and `export.as`.
* Dynamically configure task parameters (e.g., construct API request bodies or URIs).

## Syntax and Evaluation

In the default `strict` mode, write a jq expression between `${` and `}`. If the expression contains
YAML punctuation, enclose the entire value in single quotes. Use jq to construct a string with computed
parts within one expression.

The following workflow reads a customer name and item ID from the input. It also supplies a default
when the input omits `quantity`.

<code-block lang="yaml" src="workflows/dsl-runtime-expressions/format-line.yaml"/>

## Expression Language

The default is [jq](https://jqlang.github.io/jq/). The workflow's top-level `evaluate` object can select
a language and evaluation mode. The DSL's `loose` mode attempts to evaluate unwrapped values and keeps
the original value when evaluation fails.

## Available Arguments

During evaluation, the runtime makes several predefined variables (arguments) available within the expression scope.
These provide access to the current state of the workflow and task execution.

* **`.` (Dot)**: In jq, the single dot (`.`) usually represents the **primary input data** being evaluated by the
  expression. For example:
    * In `task.input.from`, `.` is the task's *raw input*.
    * In `task.output.as`, `.` is the task's *raw output*.
    * In `task.export.as`, `.` is the task's *transformed output*.
    * In `task.if`, `.` is the task's *transformed input*.
    * *Avoid confusion*: While `.` represents the immediate data, use the explicit arguments like `$input`, `$output`,
      `$context` when you need to be certain about which piece of data you are accessing, especially in complex
      expressions.

* **`$context`**: (Object) The workflow's current shared context data. This is modified by `task.export.as`.
* **`$input`**: (Any) The task's **transformed input** (i.e., after `task.input.from` has been applied).
* **`$output`**: (Any) The task's **transformed output** (i.e., after `task.output.as` has been applied).
* **`$secrets`**: (Object) A key/value map for resolving secrets in `input.from`. For safe handling, see [Secrets](dsl-secrets.md).
* **`$authorization`**: (AuthorizationDescriptor) Describes the resolved authorization details if the task uses
  authentication.
* **`$task`**: (TaskDescriptor) Describes the current task being executed.
* **`$workflow`**: (WorkflowDescriptor) Describes the current workflow instance.
* **`$runtime`**: (RuntimeDescriptor) Describes the runtime environment executing the workflow.

### Argument Structures

A descriptor groups information about the current runtime, workflow, task, or authorization.

#### Runtime Descriptor (`$runtime`)

* `name`: (String) Runtime name.
* `version`: (String) Runtime version.
* `metadata`: (Object) Implementation-specific key-value pairs.

#### Workflow Descriptor (`$workflow`)

* `id`: (String) Unique ID of the current workflow execution.
* `definition`: (Object) The parsed workflow definition.
* `input`: (Any) The *raw* workflow input (before `workflow.input.from`).
* `startedAt`: (DateTimeDescriptor) Workflow start time.

Here, a task exports the original order ID to `$context` and passes an updated item count to the next step.
The final task combines the immediate input, the exported data, and the original workflow input.

<code-block lang="yaml" src="workflows/dsl-runtime-expressions/read-expression-scope.yaml"/>

#### Task Descriptor (`$task`)

* `name`: (String) The task's name (e.g., "processData").
* `reference`: (String) The task's unique path identifier (e.g., "/do/1/myTask").
* `definition`: (Object) The parsed task definition.
* `input`: (Any) The task's *raw* input (before `task.input.from`).
* `output`: (Any) The task's *raw* output (before `task.output.as`). Note: This is only available *after* the task
  executes, primarily for `output.as` and `export.as` expressions.
* `startedAt`: (DateTimeDescriptor) Task start time.

#### Authorization Descriptor (`$authorization`)

* `scheme`: (String) Resolved scheme (e.g., "Bearer").
* `parameter`: (String) Resolved parameter (e.g., the token).

#### DateTime Descriptor (used within `$workflow.startedAt`, `$task.startedAt`)

* `iso8601`: (String) ISO 8601 formatted date-time string (e.g., "2023-10-27T10:00:00Z").
* `epoch.seconds`: (Integer) Seconds since Unix epoch.
* `epoch.milliseconds`: (Integer) Milliseconds since Unix epoch.

### Argument Availability

The following table shows which arguments are available when evaluating different DSL properties:

| Runtime Expression    | Evaluated On Data (`.`)                    | Produces                   | `$context` | `$input` | `$output` | `$secrets` | `$task` | `$workflow` | `$runtime` | `$authorization` |
|:----------------------|:-------------------------------------------|:---------------------------|:-----------|:---------|:----------|:-----------|:--------|:------------|:-----------|:-----------------|
| Workflow `input.from` | Raw workflow input                         | Transformed workflow input |            |          |           | ✔          |         | ✔           | ✔          |                  |
| Task `input.from`     | Raw task input (output from previous task) | Transformed task input     | ✔          |          |           | ✔          | ✔       | ✔           | ✔          |                  |
| Task `if`             | Transformed task input                     | Boolean                    | ✔          | ✔        |           |            | ✔       | ✔           | ✔          |                  |
| Task definition\*     | Transformed task input                     | Task execution parameters  | ✔          | ✔        |           |            | ✔       | ✔           | ✔          | ✔                |
| Task `output.as`      | Raw task output                            | Transformed task output    | ✔          | ✔        |           |            | ✔       | ✔           | ✔          | ✔                |
| Task `export.as`      | Transformed task output                    | New `$context` value       | ✔          | ✔        | ✔         |            | ✔       | ✔           | ✔          | ✔                |
| Workflow `output.as`  | Transformed output of the *last* task      | Final workflow output      | ✔          |          |           |            |         | ✔           | ✔          |                  |

*\*Refers to expressions used *within* a task's configuration, like constructing an HTTP body or URI.*

## Error Handling

A failed evaluation is an expression error with status 400. In jq, an absent object field yields
`null`. An operation with an incompatible value, such as converting `"many"` to a number, can fail.
Use [Try](dsl-task-try.md) to handle that failure.

<code-block lang="yaml" src="workflows/dsl-runtime-expressions/check-quantity-expression.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f format-line.yaml
bin/lemline instance start examples format-line --input '{"customer":{"name":"Ada"},"item":{"id":"SKU-1"}}'
bin/lemline definition post -f read-expression-scope.yaml
bin/lemline instance start examples read-expression-scope --input '{"orderId":"O-100","count":2}'
bin/lemline definition post -f check-quantity-expression.yaml
bin/lemline instance start examples check-quantity-expression --input '{"quantity":"3"}'
bin/lemline instance start examples check-quantity-expression --input '{"quantity":"many"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `format-line` | `{"message":"Ada selected SKU-1","quantity":1}` |
| `read-expression-scope` | `{"orderId":"O-100","adjustedCount":3,"originalCount":2}` |
| `check-quantity-expression` with `{"quantity":"3"}` | `{"quantity":3}` |
| `check-quantity-expression` with `{"quantity":"many"}` | The catch returns status `400` and an expression error type. |
