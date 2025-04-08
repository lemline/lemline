# Runtime Expressions

## Purpose

Runtime expressions are dynamic elements within the Serverless Workflow DSL that enable flexible and adaptable workflow
behaviors. They provide a powerful way to:

* Access and manipulate data during workflow execution (e.g., input data, context data, task outputs).
* Evaluate conditions for tasks like `if`, `while` (in `For` loops), and `when` (in `Switch` and `Try` tasks).
* Transform data structures using `input.from`, `output.as`, and `export.as`.
* Dynamically configure task parameters (e.g., construct API request bodies or URIs).

## Syntax and Evaluation

By default, expressions are written using the syntax of the configured expression language. The standard DSL uses JQ.

* **JQ Syntax**: Expressions typically access data using dot notation (e.g., `.fieldName`, `.user.id`).
* **DSL Embedding**: Within the YAML workflow definition, expressions are usually embedded within strings prefixed with
  `@`. The runtime automatically identifies and evaluates these.
    * Example: `message: "${ .user.name } processed item ${ .item.id }"`
* **Strict vs. Loose Mode (Conceptual)**: While the DSL documentation mentions modes like `strict` (`${...}`) and
  `loose`, the common practice and examples strongly favor the `@` prefix for automatic evaluation by the runtime. Using
  `${...}` might be supported by specific runtimes or language extensions but isn't the primary method shown.

## Expression Language

* **Default**: The default and mandatory expression language is **JQ** ([
  `jq` documentation](https://jqlang.github.io/jq/)). The implementation uses the `net.thisptr.jackson.jq` library (see
  `com.lemline.sw.expressions.JQExpression.kt`).
* **Configuration**: Runtimes *may* support other languages. If so, the language for a workflow can typically be
  specified using the top-level `evaluate.language` property in the workflow definition (refer to the
  [Resources Configuration Overview](dsl-resources-configuration-overview.md)).

## Available Arguments

During evaluation, the runtime makes several predefined variables (arguments) available within the expression scope.
These provide access to the current state of the workflow and task execution.

* **`.` (Dot)**: In JQ, the single dot (`.`) usually represents the **primary input data** being evaluated by the
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
* **`$secrets`**: (Object) A key/value map of secrets available to the workflow. **Caution**: Use with care, as
  embedding secrets directly in expressions or passing them as inputs might expose sensitive data in logs or outputs.
* **`$authorization`**: (AuthorizationDescriptor) Describes the resolved authorization details if the task uses
  authentication.
* **`$task`**: (TaskDescriptor) Describes the current task being executed.
* **`$workflow`**: (WorkflowDescriptor) Describes the current workflow instance.
* **`$runtime`**: (RuntimeDescriptor) Describes the runtime environment executing the workflow.

### Argument Structures

These structures define the data available within the descriptor arguments. See `com.lemline.sw.expressions.scopes.*`
classes for implementation details.

#### Runtime Descriptor (`$runtime`)

* `name`: (String) Runtime name (e.g., "lemline").
* `version`: (String) Runtime version (e.g., "1.0.0-SNAPSHOT").
* `metadata`: (Object) Implementation-specific key-value pairs.

#### Workflow Descriptor (`$workflow`)

* `id`: (String) Unique ID of the current workflow execution.
* `definition`: (Object) The parsed workflow definition.
* `input`: (Any) The *raw* workflow input (before `workflow.input.from`).
* `startedAt`: (DateTimeDescriptor) Workflow start time.

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
| Task `if`             | Transformed task input                     | Boolean                    | ✔          | ✔        |           | ✔          | ✔       | ✔           | ✔          |                  |
| Task definition\*     | Transformed task input                     | Task execution parameters  | ✔          | ✔        |           | ✔          | ✔       | ✔           | ✔          | ✔                |
| Task `output.as`      | Raw task output                            | Transformed task output    | ✔          | ✔        |           | ✔          | ✔       | ✔           | ✔          | ✔                |
| Task `export.as`      | Transformed task output                    | New `$context` value       | ✔          | ✔        | ✔         | ✔          | ✔       | ✔           | ✔          | ✔                |
| Workflow `output.as`  | Transformed output of the *last* task      | Final workflow output      | ✔          |          |           | ✔          |         | ✔           | ✔          |                  |

*\*Refers to expressions used *within* a task's configuration, like constructing an HTTP body or URI.*

**Note on `$secrets`**: While available in many places, it's generally safest to restrict usage primarily to
`task.input.from` or specific configurations (like authentication blocks) where the runtime can handle them securely,
rather than embedding them directly into general-purpose fields like log messages or HTTP bodies.

## Error Handling

If the evaluation of a runtime expression fails (e.g., due to invalid syntax, accessing a non-existent field in JQ), the
runtime raises a standard error:

* **Type**: `https://serverlessworkflow.io/spec/1.0.0/errors/expression`
* **Status**: `400` (Bad Request)

This error can be caught and handled using a [`Try`](dsl-task-try.md) task. 