# Fork

## Purpose

The `Fork` task allows workflows to execute multiple defined subtasks (branches) concurrently.

This enables parallel processing, potentially improving the overall efficiency and speed of the workflow by executing
independent tasks simultaneously.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-task-fork/fork-customer-product.yaml"/>

The `customer` and `product` branches make independent requests. With `compete` omitted, the workflow waits for both.
`combineResults` reads the customer at `.[0]` and the product at `.[1]`.

### Additional Examples

#### Example: Competing Branches (`compete: true`)

<code-block lang="yaml" src="workflows/dsl-task-fork/fork-quote-race.yaml"/>

The shop delays the economy response by eight seconds. With `compete: true`, the workflow passes the first
successful quote straight to `processWinningQuote` as an object.

#### Example: Output Array Structure (`compete: false`)

<code-block lang="yaml" src="workflows/dsl-task-fork/fork-output-array.yaml"/>

The workflow has no tasks after the fork, so its output is the branch array itself. The profile is first and the
orders are second.

## Configuration Options

### `fork` (Fork, Required)

This object defines the concurrent execution.

* **`branches`** (List\<TaskItem\>, Required): A list of `Task Items`, each defining a named branch containing a task to
  be executed concurrently with the others. Each branch operates on the same `transformedInput` that was passed to the
  `Fork` task.

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

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f fork-customer-product.yaml
bin/lemline instance start examples fork-customer-product --input '{"customerId":"C-100","productId":"SKU-1"}'
bin/lemline definition post -f fork-quote-race.yaml
bin/lemline instance start examples fork-quote-race --input '{"orderId":"O-120"}'
bin/lemline definition post -f fork-output-array.yaml
bin/lemline instance start examples fork-output-array
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `fork-customer-product` | `{"customer":"Ada Lovelace","productId":"SKU-1"}` |
| `fork-quote-race` | `{"carrier":"express","price":9}` |
| `fork-output-array` | `[{"profile":{"name":"Alice","id":123}},{"orders":[{"orderId":"O-100"},{"orderId":"O-101"}]}]` |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
