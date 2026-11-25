# For

## Purpose

The `For` task provides a mechanism to iterate over a collection of items (an array or list). For each item in the
collection, it can conditionally execute a defined block of sub-tasks (defined within a `do` block).

This is useful for processing arrays or lists of data, applying the same set of operations to each element, and
potentially filtering which elements are processed based on a condition.

## Basic Usage

Here's an example of iterating over an array of numbers and performing an action for each:

<code-block lang="yaml" src="workflows/dsl-task-for/for-loop-basic.yaml"/>

The `processNumbers` task reads the `numbers` array from the workflow input. Each iteration uses the default
variables `$item` and `$index` to return the doubled number and its zero-based position.

## Configuration Options

### `for` (Object, Required)

This object defines the core iteration parameters.

* **`in`** (String, Required): A [Runtime Expression](dsl-runtime-expressions.md) that **must** evaluate to an
  array/list. The workflow iterates over the elements of this array.
* **`each`** (String, Optional): Specifies the variable name used within the `do` block's scope to access the current
  item being processed. Defaults to `item` (accessible as `$item` in expressions).
* **`at`** (String, Optional): Specifies the variable name used within the `do` block's scope to access the zero-based
  index of the current item being processed. Defaults to `index` (accessible as `$index` in expressions).

<code-block lang="yaml" src="workflows/dsl-task-for/for-object-required.yaml"/>

### `do` (List<String: Object>, Required)

Defines the block of tasks to execute for each item in the collection (subject to the `while` condition).

The tasks within this block execute sequentially for each iteration. They have access to the current item and index via
the variables defined by `for.each` and `for.at`, which are injected into the runtime expression scope.

<code-block lang="yaml" src="workflows/dsl-task-for/read-products-in-order.yaml"/>

### `while` (String, Optional)

An optional [Runtime Expression](dsl-runtime-expressions.md) evaluated *before* executing the `do` block for each item (
including the first item).

If the expression evaluates to `false`, the `do` block for the current item is skipped, and the loop terminates.
This example tests a counter in the current input, then decrements it in the body. The next condition sees the
previous iteration’s output.

<code-block lang="yaml" src="workflows/dsl-task-for/while-string-optional.yaml"/>

> **Lemline**
>
> The condition can read the current input and context. Referencing `$item` directly in `while` faults with an expression error. Use the loop variable inside a task in the body. [Issue #121](https://github.com/lemline/lemline/issues/121) tracks this limitation.

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

**Note**:

* the `transformedOutput` of each iteration (or the `transformedInput` for the first one) is the `rawOutput` of the next
  one.
* The final `rawOutput` of the `For` task (which feeds into its `output.as`/`export.as`) is the
  `transformedOutput` of the *last* successfully executed iteration's `do` block, or the `For` task's own
  `transformedInput` if the loop didn't execute at all (e.g., empty `for.in` or initial `while` was false).

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

**Note**: A `then: exit` directive within the `do` block of a `For` task will terminate the loop immediately,
skipping any remaining items in the `for.in` collection.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. In the setup directory, save each workflow under the file name used below. Use the worker terminal to check the output of each instance.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f for-loop-basic.yaml
bin/lemline instance start examples for-loop-basic --input '{"numbers":[1,2,3]}'
bin/lemline definition post -f for-object-required.yaml
bin/lemline instance start examples for-object-required --input '{"users":["Ada","Alan"]}'
bin/lemline definition post -f read-products-in-order.yaml
bin/lemline instance start examples read-products-in-order --input '{"products":[{"id":"SKU-1"},{"id":"SKU-2"}]}'
bin/lemline definition post -f while-string-optional.yaml
bin/lemline instance start examples while-string-optional --input '{"numbers":[12,8,15],"remaining":2}'
```

| Example | Expected output |
|---|---|
| `for-loop-basic` | `{"position":2,"doubled":6}` |
| `for-object-required` | `{"item":"Alan","index":1}` |
| `read-products-in-order` | `{"productId":"SKU-2"}` |
| `while-string-optional` | `{"value":8,"remaining":0}` |

Check the worker terminal for the results. Then run this command from the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
