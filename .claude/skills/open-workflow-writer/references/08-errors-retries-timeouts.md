# Errors, retries and timeouts

Bound retries and isolate each retried side effect in its own `try`. Ensure a repeated payment or reservation uses an idempotency key honored by that service. Set an initial delay for exponential backoff. Verify exhaustion raises the intended terminal error and emits no success event.

Lemline’s HTTP executor currently reports an unsuccessful response with a runtime error title beginning with the HTTP status, e.g. `HTTP 503:`. The order example matches transient HTTP statuses and connection or socket timeout exceptions. Other failure classes need an explicit policy.

Use inline `timeout.after` on a `listen` task for an enforced deadline. The current listener does not resolve a named timeout reference. Workflow-level and HTTP task timeouts can be expressed in the DSL but do not override the runner’s fixed HTTP limits. State this limitation when a deadline is required.

When a retry policy declines another attempt and the catch has no recovery tasks or `then` directive, the original error propagates. An enclosing catch can handle the error. Use `catch.do` to run a fallback. When the workflow must fault, verify that no later success action runs.

[Lemline documentation](../../../../lemline-docs/topics/dsl-timeouts.md)
