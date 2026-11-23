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

In this example, `validateOrder`, `checkInventory`, and `processPayment` run one after the other.

## Conditional Execution: The `if` Property

All tasks support an optional `if` property, which allows for conditional execution based on the current state.

* **Type**: `string` (Runtime Expression)
* **Required**: No

The value of the `if` property is a [Runtime Expression](dsl-runtime-expressions.md) that **must** evaluate to a
boolean (`true` or `false`).

* **Evaluation**: The expression is evaluated *before* the main logic of the task begins, using the task's *transformed
  input* and the current context.
* **Effect**:
    * If the expression evaluates to `true` (or if the `if` property is omitted), the task executes normally.
    * If the expression evaluates to `false`, the entire task is skipped and the workflow continues as this task does
      not exist (e.g. the workflow *does not follow* that skipped task's `then` directive to determine the next step).

<code-block lang="yaml" src="workflows/dsl-flow-control/conditional-execution-the-if-property.yaml"/>

## Continuation: The `then` Directive

The `then` property can be added to most task definitions to explicitly control which task executes next after the
current task completes successfully.

* **Type**: `string` (Task Name) |  (`continue`, `exit`, `end`)
* **Required**: No

### Targeting a Specific Task

You can provide the **name** of another task within the *same scope* (i.e., at the same level in the `do` list) as the
value for `then`. This creates a jump or branch in the execution path.

<code-block lang="yaml" src="workflows/dsl-flow-control/targeting-a-specific-task.yaml" include-lines="6-18"/>

A critical rule is that a `then` directive specifying a task name **can only target tasks declared within the same
scope (level)**. You cannot use `then` to jump *into* or *out of* nested `Do` blocks or other flow constructs directly
by name.

<code-block lang="yaml" src="workflows/dsl-flow-control/targeting-a-specific-task-2.yaml" include-lines="7-18"/>

To achieve jumps across scopes, you typically use `exit` to return control to the parent scope, which can then direct
the flow using its own sequence or `then` directives.

### Using Flow Directives

Instead of a task name, `then` can use specific keywords (Flow Directives) to control the flow in predefined ways:

1. **`continue`** (Default):
    * **Meaning**: Explicitly specifies the default behavior - proceed to the next task in the declaration order.
    * **Usage**: Rarely needed unless overriding a default behavior in a specific context or for clarity.

      <code-block lang="yaml" src="workflows/reference/continue-to-next-task.yaml" include-lines="7-13"/>
    * **Data Flow**: as usual, the `raw input` of the next task is set to the `transformed output` of the task that
      called
      `continue`.

2. **`exit`**:
    * **Meaning**: Stops processing the *current* sequence of tasks (e.g., the current `Do` block or `For` loop
      iteration) and transfers control back to the parent flow construct. The parent then determines its next step (
      often based on its *own* `then` directive or sequence).
    * **Usage**: Useful for early termination of a sub-flow or loop based on a condition met within it.

       <code-block lang="yaml" src="workflows/dsl-flow-control/using-flow-directives.yaml" include-lines="7-23"/>
    * **Data Flow**: The `raw output` of the parent task is set to the `transformed output` of the task that called
      `exit`.

3. **`end`**:
    * **Meaning**: Gracefully terminates the execution of the *entire* workflow instance immediately.
    * **Usage**: Used for scenarios where processing should stop completely based on a certain condition or outcome,
      without executing any further tasks.

      <code-block lang="yaml" src="workflows/dsl-flow-control/using-flow-directives-2.yaml" include-lines="7-16"/>
    * **Data Flow**: the `raw output` of the workflow instance is set to the `transformed output` of the task that
      called
      `end`.

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
