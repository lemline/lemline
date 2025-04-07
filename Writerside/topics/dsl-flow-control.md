# Flow Control and Directives

## Purpose

Flow control determines the order in which tasks are executed within a Serverless Workflow. While the default behavior
is simple sequential execution, the DSL provides explicit mechanisms, primarily through the `then` directive, to create
more complex and conditional execution paths.

## Default Flow: Sequential Execution

By default, if no explicit flow control is specified, tasks within a sequence (like the top-level `do` block or the
block within a `Do` task) execute in the order they are declared in the YAML file.

```yaml
document:
  dsl: '1.0.0'
  # ...
do:
  - taskA: # Executes first
      set: { message: "Hello" }
  - taskB: # Executes second, receives output of taskA
      set: { fullMessage: "${ .message + ' World' }" }
  - taskC: # Executes third, receives output of taskB
      call: log
      with:
        text: "${ .fullMessage }"
```

In this example, `taskA`, `taskB`, and `taskC` run one after the other because no `then` directives are used.

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

```yaml
do:
  - checkInput:
      set:
        # Assume input might be { "process": true } or { "process": false }
        message: "Checking input..."
  - conditionalTask:
      if: "${ .process }" # Only run if input field 'process' is true
      call: doSomethingImportant
      # 'then' would be not followed if 'if' was false
      then: nextStep
  - taskAfterConditional: # This task is skipped if conditionalTask runs
      call: log
      with:
        message: "Conditional task was skipped."
      then: nextStep
  - nextStep:
    # ... execution continues here ...
```

## Continuation: The `then` Directive

The `then` property can be added to most task definitions to explicitly control which task executes next after the
current task completes successfully.

* **Type**: `string` (Task Name) |  (`continue`, `exit`, `end`)
* **Required**: No

### Targeting a Specific Task

You can provide the **name** of another task within the *same scope* (i.e., at the same level in the `do` list) as the
value for `then`. This creates a jump or branch in the execution path.

```yaml
do:
  - start:
      set: { value: 1 }
      then: processValue # Jump to processValue after start
  - skippedTask:
      # This task is skipped because 'start' jumps over it
      set: { value: 2 }
  - processValue:
      set: { processed: "${ .value * 10 }" }
      # Default: continues sequentially to 'finish'
  - finish:
      call: log
      with: { result: "${ .processed }" }
```

A critical rule is that a `then` directive specifying a task name **can only target tasks declared within the same
scope (level)**. You cannot use `then` to jump *into* or *out of* nested `Do` blocks or other flow constructs directly
by name.

```yaml
do:
  - outerTask1:
      do: # Inner Scope 1
        - innerTaskA:
          # ...
          # then: outerTask2 # INVALID: Cannot target task outside inner scope
        - innerTaskB:
            # ...
            then: exit # VALID: Exits Inner Scope 1
  - outerTask2:
    # ...
```

To achieve jumps across scopes, you typically use `exit` to return control to the parent scope, which can then direct
the flow using its own sequence or `then` directives.

### Using Flow Directives

Instead of a task name, `then` can use specific keywords (Flow Directives) to control the flow in predefined ways:

1. **`continue`** (Default):
    * **Meaning**: Explicitly specifies the default behavior - proceed to the next task in the declaration order.
    * **Usage**: Rarely needed unless overriding a default behavior in a specific context or for clarity.
      ```yaml
      - taskX:
          # ...
          then: continue # Explicitly go to taskY next
      - taskY:
          # ...
      ```
    * **Data Flow**: as usual, the `raw input` of the next task is set to the `transformed output` of the task that
      called
      `continue`.

2. **`exit`**:
    * **Meaning**: Stops processing the *current* sequence of tasks (e.g., the current `Do` block or `For` loop
      iteration) and transfers control back to the parent flow construct. The parent then determines its next step (
      often based on its *own* `then` directive or sequence).
    * **Usage**: Useful for early termination of a sub-flow or loop based on a condition met within it.
       ```yaml
       - parentTask:
          do:
            - checkCondition:
                if: "${ .status == 'done' }"
                then: exit # Exit this inner 'do' block
            - processNormally: # Skipped if condition was 'done'
                # ...
            - taskAfterExit: # Skipped if condition was 'done'
                # ...
          then: finalStep # Parent continues here if inner block exited or completed normally
       - finalStep:
           # ...
       ```
    * **Data Flow**: The `raw output` of the parent task is set to the `transformed output` of the task that called
      `exit`.

3. **`end`**:
    * **Meaning**: Gracefully terminates the execution of the *entire* workflow instance immediately.
    * **Usage**: Used for scenarios where processing should stop completely based on a certain condition or outcome,
      without executing any further tasks.
      ```yaml
      - criticalCheck:
          call: getSystemStatus
      - decideTermination:
          if: "${ .status == 'FATAL' }"
          then: end # Stop the whole workflow
      - continueNormalProcessing: # Skipped if status was FATAL
          # ...
      ```
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
  raise a `Configuration` error (e.g., `https://serverlessworkflow.io/spec/1.0.0/errors/configuration`).
* **Invalid Directive**: Using an unknown string (that isn't a valid task name or `continue`/`exit`/`end`) in the `then`
  property will also cause the workflow to raise a `Configuration` error.
