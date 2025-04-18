# For

## Purpose

The `For` task provides a mechanism to iterate over a collection of items (an array or list). For each item in the
collection, it can conditionally execute a defined block of sub-tasks (defined within a `do` block).

This is useful for processing arrays or lists of data, applying the same set of operations to each element, and
potentially filtering which elements are processed based on a condition.

## Basic Usage

Here's an example of iterating over an array of numbers and performing an action for each:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: for-loop-basic
  version: '1.0.0'
do:
  - setup:
      set:
        numbers: [ 1, 2, 3, 4, 5 ]
  - processNumbers:
      for:
        in: "${ .numbers }" # Expression evaluating to the collection to iterate
        # each: item       # Default variable name for the current item
        # at: index        # Default variable name for the current index
      do:
        - logItem:
            # Inside the 'do' block, 'item' and 'index' are available in the scope
            call: log # Assuming a 'log' function exists
            with:
              message: "Processing item ${ $index }: ${ $item }"

# Workflow output after 'processNumbers' will typically be the output of 'setup':
# { "numbers": [1, 2, 3, 4, 5] }
# The 'For' task itself doesn't aggregate results from the loop by default.
```

In this example, the `processNumbers` task iterates over the `numbers` array provided by the `setup` task. For each
number, it calls a hypothetical `log` function, accessing the current number via the default `$item` variable and its
index via the default `$index` variable within the runtime expression scope.

## Configuration Options

### `for` (Object, Required)

This object defines the core iteration parameters.

* **`in`** (String, Required): A [Runtime Expression](dsl-runtime-expressions.md) that **must** evaluate to an
  array/list. The workflow iterates over the elements of this array.
* **`each`** (String, Optional): Specifies the variable name used within the `do` block's scope to access the current
  item being processed. Defaults to `item` (accessible as `$item` in expressions).
* **`at`** (String, Optional): Specifies the variable name used within the `do` block's scope to access the zero-based
  index of the current item being processed. Defaults to `index` (accessible as `$index` in expressions).

```yaml
for:
  in: "${ .users }"      # Iterate over the 'users' array from the task's input
  each: "currentUser"    # Access the current user as $currentUser
  at: "userIndex"        # Access the current index as $userIndex
```

### `do` (List<String: Object>, Required)

Defines the block of tasks to execute for each item in the collection (subject to the `while` condition).

The tasks within this block execute sequentially for each iteration. They have access to the current item and index via
the variables defined by `for.each` and `for.at`, which are injected into the runtime expression scope.

```yaml
for:
  in: "${ .products }"
  each: product
do:
  - checkStock:
      call: inventoryService
      with:
        productId: "${ $product.id }" # Access the 'id' field of the current product
  - updatePrice:
    # ... task definition that can use $product ...
```

### `while` (String, Optional)

An optional [Runtime Expression](dsl-runtime-expressions.md) evaluated *before* executing the `do` block for each item (
including the first item).

If the expression evaluates to `false`, the `do` block for the current item is skipped, and the loop terminates.
The expression is evaluated against the current task state, including
the loop variables (`$item`, `$index` by default) available in this scope.

```yaml
for:
  in: "${ .dataPoints }"
  # execute 'do' block up to the first item where value > 10
  while: "${ $item.value > 10 }"
do:
  - processHighValueItem:
    # ... task definition ...
```

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

**Note**: A `then :exit` directive within the `do` block of a `For` task will terminate the loop immediately,
skipping any remaining items in the `for.in` collection.


