# Lemline Basic Workflow Patterns

This document provides examples of fundamental workflow patterns implemented in the Open Workflow DSL as supported by Lemline. These basic patterns serve as building blocks for creating more complex workflows.

## Run the Examples

Each example below is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md) and run the commands
from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. Start the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The [set example](#set-example) is started by an event, and the [for loop example](#for-loop-example) waits for events.
Both need the tutorial publisher, saved as `tutorial-publisher.yaml`:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

A workflow started by an event must be registered before the worker starts. Stop the setup worker with Ctrl+C,
save the set example below as `set-example.yaml`, then register both and start the worker again:

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f set-example.yaml
bin/lemline listen --debug
```

Run the other commands from a second terminal in the setup directory. The worker logs `Workflow completed with output` with each result.

## Sequential Task Execution

The simplest workflow pattern is a sequence of tasks executed one after another. This is achieved using the `do` construct.

### Single Task Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/single-task-example.yaml"/>

Save it as `single-task-example.yaml`:

```bash
bin/lemline definition post -f single-task-example.yaml
bin/lemline instance start examples read-stock
```

Expect `{"sku":"SKU-1","available":100,"reserved":0}` on a fresh shop.

A single GET returns the stock of the tutorial shop.

### Multiple Sequential Tasks Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/multiple-sequential-tasks-example.yaml"/>

Save it as `multiple-sequential-tasks-example.yaml`:

```bash
bin/lemline definition post -f multiple-sequential-tasks-example.yaml
bin/lemline instance start examples stock-availability
```

Expect `{"available":100}`.

The first task reads the stock. The second returns the stock level as `available`.

## Nested Task Execution

Tasks can be nested to group related operations and create hierarchical structures.

### Nested Tasks Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/nested-tasks-example.yaml"/>

Save it as `nested-tasks-example.yaml`:

```bash
bin/lemline definition post -f nested-tasks-example.yaml
bin/lemline instance start examples stock-summary
```

Expect `{"message":"SKU-1 available: 100"}`.

The nested task reads the stock and extracts the level. The final task formats the count as a message.

Nesting is useful for:
- Grouping related operations
- Localizing variable scope
- Creating reusable task patterns

## Conditional Logic with Switch

The `switch` construct allows workflows to implement conditional logic, executing different tasks based on data conditions.

### Switch Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/switch-example.yaml"/>

Save it as `switch-example.yaml`:

```bash
bin/lemline definition post -f switch-example.yaml
bin/lemline instance start examples order-type-routing --input '{"orderType":"electronic"}'
bin/lemline instance start examples order-type-routing --input '{"orderType":"physical"}'
bin/lemline instance start examples order-type-routing --input '{"orderType":"gift-card"}'
```

Expect `{"validate":true,"status":"fulfilled"}`, then `{"inventory":"clear","items":1,"address":"12 Main St"}`, then `{"log":"warn","message":"Unknown order type"}`.

This example demonstrates:
1. A switch statement evaluating `.orderType`
2. Three different paths based on the condition
3. Each path setting different variables and potentially ending the workflow
4. A default case for handling unknown types

Key aspects of `switch`:
- Multiple conditions can be evaluated in sequence
- The first matching condition is executed
- The `default` case handles situations where no conditions match
- `then` can direct flow to other named tasks in the workflow

## Loop Patterns

Workflows often need to iterate over collections of data or repeat tasks until certain conditions are met.

### For Loop Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/for-loop-example.yaml"/>

Save it as `for-loop-example.yaml`:

```bash
bin/lemline definition post -f for-loop-example.yaml
bin/lemline instance start examples pack-order-items --input '{"items":[{"id":"SKU-1"},{"id":"SKU-2"},{"id":"SKU-3"}]}'
```

The instance waits for the first item. Publish two packing events, the second marked as the last item:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.item.packed","data":{"lastItem":false}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.item.packed","data":{"lastItem":true}}'
```

Wait about 10 seconds between the two events: the second iteration starts listening only after the first event has
been consumed, and an event resumes only the instances already waiting for it. The `while` condition is checked before each item; after the second event, `stop` is true, so the loop ends
without waiting for SKU-3. Expect `{"lastPacked":"SKU-2","position":1,"stop":true}`.

This example demonstrates:
1. Iterating over a collection (`$workflow.input.items`)
2. The current item is accessible as `$item`
3. The index is accessible as `$index`
4. A condition (`while`) is checked before each iteration and can end the loop early
5. Inside the loop, an event listener waits for a specific event
6. Each iteration's result is the next iteration's input

Key aspects of `for`:
- Iterates over arrays or objects
- Can track iteration index with the `at` property
- Can include additional conditions with `while`
- Contains a `do` block with tasks to execute for each iteration

## Data Manipulation with Set

The `set` construct allows workflows to create or modify variables.

### Set Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/set-example.yaml"/>

You registered it before starting the worker. Publish its event:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.basic.triggered","data":{"orderId":"ORD-1004"}}'
```

About 10 seconds later, a `started-by-event` instance completes with `{"startEvent":{"orderId":"ORD-1004"}}`: the
worker picks up events for event-started workflows every 10 seconds, and the workflow input is the list of events
that started it.

This example demonstrates:
1. Setting a variable `startEvent` to the first element of the workflow input
2. The workflow is triggered by an event

Additional `set` capabilities:
- Set multiple variables in a single step
- Use JQ expressions for data transformation
- Reference existing variables and workflow context

## Advanced Set Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/advanced-set-example.yaml"/>

Save it as `advanced-set-example.yaml`:

```bash
bin/lemline definition post -f advanced-set-example.yaml
bin/lemline instance start examples advanced-set
```

Expect `enrichedUser` with the user fields, `lastAccess` set to the timestamp as a number of seconds, `isAdmin: true` and `permissionCount: 2`.

This advanced example shows:
1. Setting nested object structures
2. Using the JQ expression language for data manipulation
3. Combining and transforming existing variables
4. Using built-in functions like `now()` and `length()`

## Combining Patterns

These basic patterns can be combined to create more complex workflows.

### Combined Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/combined-example.yaml"/>

Save it as `combined-example.yaml`:

```bash
bin/lemline definition post -f combined-example.yaml
bin/lemline instance start examples combined-patterns
```

Each item is routed by type, then logged in the shop's audit list: `E-book processed, download ready`, `Notebook processed, shipping required` and `Software processed, download ready`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

This complex example demonstrates:
1. Initializing a data structure with `set`
2. Iterating over the data with `for`
3. Using `switch` for conditional processing based on item type
4. Nesting `do` blocks within conditions
5. Transforming data based on conditions
6. Using the transformed data in subsequent tasks

## Best Practices

When implementing these basic patterns:

1. **Descriptive task names**: Use clear, meaningful names for tasks to make workflows self-documenting
2. **Appropriate granularity**: Break complex tasks into manageable steps, but avoid excessive fragmentation
3. **Consistent data structure**: Maintain a predictable data structure throughout the workflow
4. **Error handling**: Consider adding try-catch blocks for operations that may fail
5. **Data validation**: Validate inputs early in the workflow
6. **Modular design**: Group related tasks for better organization and potential reuse

## Conclusion

These basic workflow patterns form the foundation for building complex business processes in Lemline. By understanding and combining these patterns, you can create workflows that are:

- Easy to understand
- Maintainable
- Adaptable to changing requirements
- Capable of handling complex business logic

For examples integrating with external services, see the [Integration Examples](lemline-examples-integrations.md) document.