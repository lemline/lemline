---
title: Control Flow
---

# Flow Tasks

Flow tasks are fundamental components that control the execution path of your open workflows. They determine the
order in which tasks run, manage conditional branching, handle iterations, and orchestrate parallel execution paths.

## Flow Task Types

| Task                         | Purpose                                              |
|------------------------------|------------------------------------------------------|
| [Do](dsl-task-do.md)         | Define sequential execution of one or more tasks     |
| [For](dsl-task-for.md)       | Iterate over a collection of items                   |
| [Switch](dsl-task-switch.md) | Implement conditional branching based on data values |
| [Fork](dsl-task-fork.md)     | Execute multiple branches in parallel                |

## When to Use Flow Tasks

### Sequential Execution with Do

Use the **Do** task when you need to:

- Define a sequence of operations that should run in a specific order
- Group related tasks together for better organization
- Create nested execution blocks for complex workflows
- Implement a series of steps that build on each other

This sequence reserves stock, charges the order, and uses the charge to build a receipt.

<code-block lang="yaml" src="workflows/dsl-flow-overview/flow-order-sequence.yaml"/>

### Iterative Processing with For

Use the **For** task when you need to:

- Process each item in a collection or array
- Execute the same operation multiple times with different inputs
- Break down bulk operations into individual item processing
- Create data transformation pipelines that operate on collections

This loop computes a line total for each order item. Its output is the last line total.

<code-block lang="yaml" src="workflows/dsl-flow-overview/flow-items.yaml"/>

### Conditional Logic with Switch

Use the **Switch** task when you need to:

- Implement decision-making logic based on data values
- Create different execution paths based on conditions
- Handle multiple possible values with distinct processing for each
- Implement business rules with clear branching logic

A card payment selects the automatic route. The default case selects manual review.

<code-block lang="yaml" src="workflows/dsl-flow-overview/flow-payment-route.yaml"/>

### Parallel Execution with Fork

Use the **Fork** task when you need to:

- Execute multiple independent tasks simultaneously
- Improve workflow performance by parallelizing operations
- Process multiple streams of data concurrently
- Implement fan-out patterns for workload distribution

The product and stock requests can run independently. The last task combines their outputs.

<code-block lang="yaml" src="workflows/dsl-flow-overview/flow-product-details.yaml"/>

## Combining Flow Tasks

Flow tasks combine with each other and with other tasks. Here, a `fork` runs two checks in parallel inside a `try`, and the `catch` provides the fallback:

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f flow-order-sequence.yaml
bin/lemline instance start examples flow-order-sequence --input '{"orderId":"O-110","items":[{"id":"SKU-1","quantity":2}]}'
bin/lemline definition post -f flow-items.yaml
bin/lemline instance start examples flow-items --input '{"items":[{"productId":"SKU-1","quantity":2,"price":11},{"productId":"SKU-2","quantity":3,"price":5}]}'
bin/lemline definition post -f flow-payment-route.yaml
bin/lemline instance start examples flow-payment-route --input '{"method":"card"}'
bin/lemline instance start examples flow-payment-route --input '{"method":"invoice"}'
bin/lemline definition post -f flow-product-details.yaml
bin/lemline instance start examples flow-product-details
bin/lemline definition post -f parallel-checks-with-fallback.yaml
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"O-111"}'
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"O-112","inventoryFailures":1}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `flow-order-sequence` | `{"orderId":"O-110","paid":true,"amount":50}` |
| `flow-items` | `{"productId":"SKU-2","amount":15}` |
| `flow-payment-route` with `{"method":"card"}` | `{"route":"automatic"}` |
| `flow-payment-route` with `{"method":"invoice"}` | `{"route":"review"}` |
| `flow-product-details` | `{"productId":"SKU-1","available":98}` |
| `parallel-checks-with-fallback` with `{"orderId":"O-111"}` | An array with the stock for `SKU-1` and the express quote priced at `9`. |
| `parallel-checks-with-fallback` with `{"orderId":"O-112","inventoryFailures":1}` | `{"status":"needs-review"}` |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
