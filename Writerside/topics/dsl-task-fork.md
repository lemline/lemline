# Fork

## Purpose

The `Fork` task allows workflows to execute multiple defined subtasks (branches) concurrently.

This enables parallel processing, potentially improving the overall efficiency and speed of the workflow by executing
independent tasks simultaneously.

## Usage Example

```yaml
document:
  dsl: '1.0.0' # Assuming alpha5 or later based on reference example
  namespace: test
  name: fork-example
  version: '0.1.0'
do:
  - getData:
      # ... task to fetch initial data ...
      then: processConcurrently
  - processConcurrently:
      fork:
        # Optional: Make branches compete; first one finished provides the output
        # compete: true 
        branches:
          - processUserData:
              # This branch runs concurrently with processProductData
              call: processUserMicroservice
              with:
                userId: "${ .userId }"
          - processProductData:
              # This branch runs concurrently with processUserData
              call: processProductMicroservice
              with:
                productId: "${ .productId }"
      # Default: compete=false, waits for both branches 
      # Output is an array: [output_of_processUserData, output_of_processProductData]
      then: combineResults
  - combineResults:
    # ... task that uses the array output from the fork ...
```

In this example, after `getData`, the tasks `processUserData` and `processProductData` are executed in parallel. Since
`compete` is false (by default), the workflow waits for both branches to complete. The output passed to `combineResults`
is an array containing the results from both branches in the order they were defined.

### Additional Examples

#### Example: Competing Branches (`compete: true`)

```yaml
do:
  - fetchFastestQuote:
      fork:
        compete: true # Branches race, first one to complete wins
        branches:
          - getQuoteSourceA:
              call: http
              with:
                uri: https://api.sourceA.com/quote
                method: get
                # Assume returns { "provider": "A", "price": 100 }
          - getQuoteSourceB:
              call: http
              with:
                uri: https://api.sourceB.com/quote
                method: get
                # Assume returns { "provider": "B", "price": 105 }
      # Output is the result of whichever branch finished first
      # e.g., { "provider": "A", "price": 100 } if Source A was faster
      then: processBestQuote
  - processBestQuote:
    # ... task uses the single quote object from the winner ...
```

Here, `getQuoteSourceA` and `getQuoteSourceB` run concurrently. Because `compete: true`, the `Fork` task finishes as
soon as *one* of them completes successfully. The output passed to `processBestQuote` will be the result object from
only the faster source.

#### Example: Output Array Structure (`compete: false`)

```yaml
do:
  - gatherInfo:
      fork:
        # compete: false is the default
        branches:
          - getUserProfile:
              set: { profile: { name: "Alice", id: 123 } }
          - getUserOrders:
              set: { orders: [ { orderId: "A" }, { orderId: "B" } ] }
      # Output is an array: [ { profile: { ... } }, { orders: [ ... ] } ]
      then: displayInfo
  - displayInfo:
      # Input to this task is the array from the fork
      # Access results via index: @{ .[0].profile.name }, @{ .[1].orders[0].orderId }
      call: log
      with:
        message: "User: @{ .[0].profile.name }, First Order: @{ .[1].orders[0].orderId }"
```

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