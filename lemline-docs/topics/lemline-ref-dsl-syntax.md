# DSL Syntax Reference

Lemline reads Open Workflow DSL definitions in YAML and JSON. See
[Supported Features](lemline-ref-supported-features.md) for the subset the runner executes.

## Workflow structure

A definition contains a `document` with its identity and a `do` list of named tasks.
The optional `input` and `output` sections describe data validation and transformation.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Data and expressions

Write a jq expression as `${ expression }`. Within a task, `.` is the current input,
`$workflow.input` is the original workflow input, and `$context` is the data shared by
tasks through `export.as`.

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

## Shared resources

Declare reusable authentication policies, errors, functions, retries, timeouts and
catalogs under `use`. Tasks can refer to these resources by their declared names.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

## Task and flow structure

A task list contains maps with one named task each. Constructs such as `for`, `fork`,
`switch` and `try` control flow inside the list. Use `then` to change the default
sequential flow.

<code-block lang="yaml" src="workflows/reference/map-order-items.yaml"/>

See [Task Types](lemline-ref-task-types.md) for individual constructs,
[Authentication](lemline-ref-auth.md) for credential placement, and
[How to Define a Workflow](lemline-howto-define-workflow.md) for the main authoring steps.
