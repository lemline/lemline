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

<code-block lang="yaml" src="workflows/dsl-flow-overview/sequential-execution-with-do.yaml"/>

### Iterative Processing with For

Use the **For** task when you need to:

- Process each item in a collection or array
- Execute the same operation multiple times with different inputs
- Break down bulk operations into individual item processing
- Create data transformation pipelines that operate on collections

<code-block lang="yaml" src="workflows/dsl-flow-overview/iterative-processing-with-for.yaml"/>

### Conditional Logic with Switch

Use the **Switch** task when you need to:

- Implement decision-making logic based on data values
- Create different execution paths based on conditions
- Handle multiple possible values with distinct processing for each
- Implement business rules with clear branching logic

<code-block lang="yaml" src="workflows/dsl-flow-overview/conditional-logic-with-switch.yaml"/>

### Parallel Execution with Fork

Use the **Fork** task when you need to:

- Execute multiple independent tasks simultaneously
- Improve workflow performance by parallelizing operations
- Process multiple streams of data concurrently
- Implement fan-out patterns for workload distribution

<code-block lang="yaml" src="workflows/dsl-flow-overview/parallel-execution-with-fork.yaml"/>

## Combining Flow Tasks

Flow tasks combine with each other and with other tasks. Here, a `fork` runs two checks in parallel inside a `try`, and the `catch` provides the fallback:

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

Flow tasks are the building blocks of workflow orchestration, 
enabling you to create maintainable workflows that reflect
your business processes while separating orchestration logic from the implementation of business logic. 