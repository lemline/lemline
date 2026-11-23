# Open Workflow DSL in Lemline

Open Workflow is a declarative language for composing named tasks in YAML or JSON.
A workflow defines metadata, data contracts, and the tasks that act on the data.
Lemline implements the subset described in [Supported Features](lemline-ref-supported-features.md).

## Definitions and execution

A definition has a stable identity under `document`. Each execution carries its own
input and state. A workflow instance runs the tasks under `do` in order unless a task
provides a different flow directive.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Data and expressions

A task receives input as `.`. Use `${ ... }` to evaluate a jq expression. The original
input is available as `$workflow.input`. Tasks can share values through `$context` by
exporting them with `export.as`.

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

## Resource declarations

The `use` section defines shared resources. For example, a named retry policy can be
referenced from any compatible catch block.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

See [How to Define a Workflow](lemline-howto-define-workflow.md) for authoring steps.
