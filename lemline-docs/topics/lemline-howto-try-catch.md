# Error Handling with Try-Catch

A `try` task runs a list of tasks. If a task fails, `catch` can filter the error, retry the
try block, or execute a fallback list.

## Filter an error and retry

Set `catch.errors.with` to match predefined fields such as the error type or status.
Use `catch.when` for a jq predicate over the error when the field filter is insufficient.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

## Read the caught error

The caught error is available as `$error` by default. Use `catch.as` to choose another
name. The following example reads error fields inside the catch task list.

<code-block lang="yaml" src="workflows/reference/format-error-result.yaml"/>

## Propagate failures

Errors that do not match the filter propagate to the surrounding task scope. A
fallback can also raise an error if the workflow should fault after cleanup.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

See [Retry Mechanism](lemline-howto-retry.md) and [Custom Errors](lemline-howto-custom-errors.md).

Run the [shop checkout tutorial](lemline-tutorial-order-processing.md) to observe the release before the workflow faults.
