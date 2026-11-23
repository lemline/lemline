# jq expressions in workflows

Write a workflow expression as `${ ... }`. Lemline evaluates expressions using jq.
The current input is `.`. Use `$workflow.input` for the original input and `$context`
for values exported by earlier tasks.

## Transform data

Object construction, arrays, filters and pipes are useful for selecting and reshaping
data. This task counts items and computes the order total with `map`-style iteration
and `add`.

<code-block lang="yaml" src="workflows/reference/aggregate-order-data.yaml"/>

## Compare values and branch

Use jq comparisons inside `if` or `switch.when`. A switch case without a condition
provides a fallback.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Construct strings for integrations

Use jq string interpolation for dynamic URLs and `@uri` to escape values used in the
path.

<code-block lang="yaml" src="workflows/reference/dynamic-http-endpoint.yaml"/>

See [Runtime Expressions](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl.md#runtime-expressions) for other variables and
[Time in Workflows](lemline-explain-time.md) for timestamp comparisons.
