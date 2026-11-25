---
title: Flow Control
---

<!-- Examples are validated -->

# Flow Control and Directives

## Purpose

Flow control determines the order in which tasks are executed within an Open Workflow. While the default behavior
is simple sequential execution, the DSL provides explicit mechanisms, primarily through the `then` directive, to create
more complex and conditional execution paths.

## Default Flow: Sequential Execution

By default, if no explicit flow control is specified, tasks within a sequence (like the top-level `do` block or the
block within a `Do` task) execute in the order they are declared in the YAML file.

<code-block lang="yaml" src="workflows/dsl-flow-control/default-flow-sequential-execution.yaml"/>

Each step uses the preceding step’s data. The last step returns the order ID and payment flag.

## Conditional Execution: The `if` Property

All tasks support an optional `if` property, which allows for conditional execution based on the current state.

* **Type**: `string` (Runtime Expression)
* **Required**: No

The value of the `if` property is a [Runtime Expression](dsl-runtime-expressions.md) that **must** evaluate to a
boolean (`true` or `false`).

* **Evaluation**: The expression is evaluated *before* the main logic of the task begins, using the task’s raw input and the current context.
* **Effect**:
    * If the expression evaluates to `true` (or if the `if` property is omitted), the task executes normally.
    * If the expression evaluates to `false`, the task passes its raw input to the next task in the list. Its `then` directive is ignored.

<code-block lang="yaml" src="workflows/dsl-flow-control/conditional-execution-the-if-property.yaml"/>

## Continuation: The `then` Directive

The `then` property can be added to most task definitions to explicitly control which task executes next after the
current task completes successfully.

* **Type**: `string` (Task Name) |  (`continue`, `exit`, `end`)
* **Required**: No

### Targeting a Specific Task

You can provide the **name** of another task within the *same scope* (i.e., at the same level in the `do` list) as the
value for `then`. This creates a jump or branch in the execution path.

<code-block lang="yaml" src="workflows/dsl-flow-control/targeting-a-specific-task.yaml"/>

A critical rule is that a `then` directive specifying a task name **can only target tasks declared within the same
scope (level)**. You cannot use `then` to jump *into* or *out of* nested `Do` blocks or other flow constructs directly
by name.

<code-block lang="yaml" src="workflows/dsl-flow-control/targeting-a-specific-task-2.yaml"/>

To achieve jumps across scopes, you typically use `exit` to return control to the parent scope, which can then direct
the flow using its own sequence or `then` directives.

### Using Flow Directives

Instead of a task name, `then` can use specific keywords (Flow Directives) to control the flow in predefined ways:

#### `continue`

Run the next task in declaration order. Here the second task increments the value returned by the first.

<code-block lang="yaml" src="workflows/reference/continue-to-next-task.yaml"/>

#### `exit`

Stop the current sequence and return its output to the parent. Here `exit` ends the inner sequence, and
the parent’s own `then` selects the final task.

<code-block lang="yaml" src="workflows/dsl-flow-control/using-flow-directives.yaml"/>

#### `end`

Finish the entire instance with the current task’s output. The workflow-level `output.as` still applies.

<code-block lang="yaml" src="workflows/dsl-flow-control/using-flow-directives-2.yaml"/>

## Task-Specific Flow Control

Some tasks inherently control the flow:

* **`Switch`**: Evaluates `when` conditions and uses the `then` directive of the *first matching case* to determine the
  next task.

* **`Try`**: If an error is caught and handled by a `catch.do` block, the flow continues after the `Try` task based on
  the `Try` task's own `then` directive. If an error is caught and retried, the flow loops back to the beginning of the
  `try` block. If an uncaught error occurs, the flow is interrupted.

* **`Raise`**: Immediately interrupts the normal flow and transfers control to the error handling mechanism (searching
  for a `Try`). Its `then` directive is ignored.

## Potential Errors

* **Target Not Found**: If `then` specifies a task name that does not exist within the same scope, the workflow will
  raise a `Configuration` error (e.g., `https://open-workflow-specification.org/spec/1.0.0/errors/configuration`).

* **Invalid Directive**: Using an unknown string (that isn't a valid task name or `continue`/`exit`/`end`) in the `then`
  property will also cause the workflow to raise a `Configuration` error.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f default-flow-sequential-execution.yaml
bin/lemline instance start examples default-flow-sequential-execution --input '{"orderId":"O-100"}'
bin/lemline definition post -f conditional-execution-the-if-property.yaml
bin/lemline instance start examples conditional-execution-the-if-property --input '{"expedite":true}'
bin/lemline instance start examples conditional-execution-the-if-property --input '{"expedite":false}'
bin/lemline definition post -f targeting-a-specific-task.yaml
bin/lemline instance start examples targeting-a-specific-task
bin/lemline definition post -f targeting-a-specific-task-2.yaml
bin/lemline instance start examples targeting-a-specific-task-2
bin/lemline definition post -f continue-to-next-task.yaml
bin/lemline instance start examples continue-to-next-task
bin/lemline definition post -f using-flow-directives.yaml
bin/lemline instance start examples using-flow-directives --input '{"status":"done"}'
bin/lemline instance start examples using-flow-directives --input '{"status":"new"}'
bin/lemline definition post -f using-flow-directives-2.yaml
bin/lemline instance start examples using-flow-directives-2 --input '{"status":"FATAL"}'
bin/lemline instance start examples using-flow-directives-2 --input '{"status":"OK"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `default-flow-sequential-execution` | `{"orderId":"O-100","paid":true}` |
| `conditional-execution-the-if-property` with `{"expedite":true}` | `{"carrier":"express"}` |
| `conditional-execution-the-if-property` with `{"expedite":false}` | `{"carrier":"economy"}` |
| `targeting-a-specific-task` | `{"result":10}` |
| `targeting-a-specific-task-2` | `{"inner":"complete","outer":"complete"}` |
| `continue-to-next-task` | `{"value":2}` |
| `using-flow-directives` with `{"status":"done"}` | `{"route":"already-done","reachedParent":true}` |
| `using-flow-directives` with `{"status":"new"}` | `{"route":"processed","reachedParent":true}` |
| `using-flow-directives-2` with `{"status":"FATAL"}` | `{"result":"halted"}` |
| `using-flow-directives-2` with `{"status":"OK"}` | `{"result":"continued"}` |
