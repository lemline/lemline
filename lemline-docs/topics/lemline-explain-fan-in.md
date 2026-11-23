# Fan-out and fan-in

Fan-out distributes work. Fan-in brings the results together. The DSL has several
ways to model these patterns, depending on where the work happens.

## Run independent branches

A `fork` starts its branches concurrently. A non-competing fork joins their results
after all branches finish.

<code-block lang="yaml" src="workflows/reference/parallel-order-checks.yaml"/>

## Iterate over input data

A `for` task runs its body for each item of a collection. Bind the current value with
`each` and its index with `at`.

<code-block lang="yaml" src="workflows/reference/map-order-items.yaml"/>

## Collect results from independent services

If outside services publish events, use `listen.to.all` to require every event type.
Use correlation to scope matches to the current workflow instance.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

## Recover when a branch fails

Use `try` around the fork for a group-wide fallback. To recover inside a specific
branch, place the handler in that branch.

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

See [Parallel Tasks](lemline-howto-parallel.md) and [Event Correlation](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl-reference.md#correlation).
