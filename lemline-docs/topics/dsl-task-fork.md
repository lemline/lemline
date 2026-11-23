# Fork

## Purpose

The `Fork` task allows workflows to execute multiple defined subtasks (branches) concurrently.

This enables parallel processing, potentially improving the overall efficiency and speed of the workflow by executing
independent tasks simultaneously.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-task-fork/usage-example.yaml"/>

In this example, after `getData`, the tasks `processUserData` and `processProductData` are executed in parallel. Since
`compete` is false (by default), the workflow waits for both branches to complete. The output passed to `combineResults`
is an array containing the results from both branches in the order they were defined.

### Additional Examples

#### Example: Competing Branches (`compete: true`)

<code-block lang="yaml" src="workflows/dsl-task-fork/example-competing-branches-compete-true.yaml" include-lines="7-24"/>

Here, `getQuoteSourceA` and `getQuoteSourceB` run concurrently. Because `compete: true`, the `Fork` task finishes as
soon as *one* of them completes successfully. The output passed to `processBestQuote` will be the result object from
only the faster source.

#### Example: Output Array Structure (`compete: false`)

<code-block lang="yaml" src="workflows/dsl-task-fork/example-output-array-structure-compete-false.yaml" include-lines="6-22"/>

This demonstrates the output when `compete` is false. The `rawOutput` of the `gatherInfo` task is an array where the
first element is the output of `getUserProfile` and the second element is the output of `getUserOrders`. The
`displayInfo` task then accesses elements within this array using index notation (`.[0]`, `.[1]`).

## Configuration Options

### `fork` (Fork, Required)

This object defines the concurrent execution.

* **`branches`** (List\<TaskItem\>, Required): A list of `Task Items`, each defining a named branch containing a task to
  be executed concurrently with the others. Each branch operates on the same `transformedInput` that was passed to the
  `Fork` task.
    * *Note: The type in the reference `map[string, task][]` seems slightly unusual; it likely represents a list where
      each item implicitly has a name (key) and a task definition (value), similar to how tasks are defined in a `Do`
      block.*

* **`compete`** (Boolean, Optional, Default: `false`): Determines the completion and output behavior:
    * `false` (Default): The `Fork` task completes only after **all** branches have successfully completed. The task's
      `rawOutput` is an array containing the `transformedOutput` from each branch, in the order the branches are
      declared in the `branches` list.
    * `true`: The branches race against each other. The `Fork` task completes as soon as the **first** branch
      successfully completes. The task's `rawOutput` is the `transformedOutput` of only that single winning branch. The
      execution of other, slower branches might be implicitly cancelled by the runtime.

### Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the `Fork` task is passed identically to each branch when it starts execution.
*   The `rawOutput` of the `Fork` task depends on the `compete` flag:
    *   `compete: false`: An array of the `transformedOutput` from all completed branches, in declaration order.
    *   `compete: true`: The `transformedOutput` of the single branch that completed first.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: 
*   The `if` condition is evaluated *before* the `Fork` task starts any branches. If false, the entire task is skipped, and its `then` directive is followed immediately.
*   The `then` directive is followed only *after* the `Fork` task completes successfully based on the `compete` flag (either all branches finish, or the first competing branch finishes).
*   If any branch faults *before* the `Fork` task completes according to its `compete` mode, the entire `Fork` task typically faults immediately, and its `then` directive is *not* followed (unless the error is caught by an outer `Try` task). 