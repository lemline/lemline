# Workflow Task Types

A workflow's `do` list contains named tasks. Each entry has exactly one name with a
task configuration beneath it. See [Supported Features](lemline-ref-supported-features.md)
for which configuration variants Lemline can execute.

| Task | Purpose |
| --- | --- |
| [`do`](dsl-task-do.md) | Group tasks in a nested list |
| [`set`](dsl-task-set.md) | Construct a value from literal data and expressions |
| [`switch`](dsl-task-switch.md) | Choose a flow directive using conditions |
| [`for`](dsl-task-for.md) | Iterate over a collection |
| [`fork`](dsl-task-fork.md) | Run independent branches concurrently |
| [`wait`](dsl-task-wait.md) | Pause an instance for a duration |
| [`try`](dsl-task-try.md) | Catch errors, retry or run a fallback |
| [`raise`](dsl-task-raise.md) | Raise a defined or inline workflow error |
| [`emit`](dsl-task-emit.md) | Publish a CloudEvent |
| [`listen`](dsl-task-listen.md) | Wait for matching CloudEvents |
| [`call`](dsl-call-overview.md) | Invoke a service or workflow function |
| [`run`](dsl-run-overview.md) | Invoke a child workflow or other process |

## Connect tasks

A task can provide an `if` condition, configure data with `input`, `output`
and `export`, or change the default sequence with `then`.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

See [DSL Syntax Reference](lemline-ref-dsl-syntax.md) for workflow-level sections
and expression scopes.
