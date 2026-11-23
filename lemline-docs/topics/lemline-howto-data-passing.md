---
title: How to pass data between tasks
---

# How to pass data between tasks

By default, the transformed output of one task becomes the input of the next task.
Use `input.from` when a task needs to select or reshape its input, and `output.as`
when a task needs to adapt its result for the following step.

## Shape input and output

The workflow below transforms its input, calculates a result and returns selected
fields in its output.

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

## Share a value across tasks

Use `export.as` to update `$context`. Merge the new value with the existing context
when earlier context entries must remain accessible. Use `$workflow.input` to read
values supplied when the workflow started.

<code-block lang="yaml" src="workflows/reference/share-order-context.yaml"/>

See [Data Flow](dsl-data-flow.md) for validation order and expression scopes.
