# Retrying failed workflow tasks

Configure retries under `catch.retry` on a `try` task. Use `delay` for the initial
interval and `limit.attempt.count` to bound attempts. With a backoff configured,
the runner derives subsequent intervals from the configured strategy.

## Reuse a retry policy

Put a named policy under `use.retries` and reference it from `catch.retry`. In this example,
the catch block handles status 503. If retries are exhausted, the catch task list returns
a fallback result.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

To use the same policy in one place, put the policy object directly under `catch.retry`.
Use the `when` or `exceptWhen` predicates to limit retries further. See the
[Try Task reference](dsl-task-try.md) for error matching and fallback behavior.
