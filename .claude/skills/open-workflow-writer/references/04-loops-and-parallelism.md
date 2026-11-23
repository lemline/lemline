# Loops and parallel execution

For a collection use `for.in: ${ .items }` and give each element a name with `for.each`. Read that variable inside the body, e.g. `$item.id`. Account for an empty collection and preserve any totals needed after the loop.

`fork.branches` is a single list of named tasks, using the same shape as `do`. For several steps in one branch, give that named task its own `do`. Set `compete: true` when the first successful branch should win. A losing branch may already have initiated a remote action.

[Lemline documentation](../../../../lemline-docs/topics/dsl-task-fork.md)
