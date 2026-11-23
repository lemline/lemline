# Errors, retries and timeouts

Bound retries and isolate each retried side effect in its own `try`. Ensure a repeated payment or reservation uses an idempotency key honored by that service. Set an initial delay for exponential backoff. Verify exhaustion raises the intended terminal error and emits no success event.

Lemline’s HTTP executor currently reports an unsuccessful response with a runtime error title beginning with the HTTP status, e.g. `HTTP 503:`. The order example matches transient HTTP statuses and connection or socket timeout exceptions. Other failure classes need an explicit policy.

Lemline enforces `listen` timeouts. Workflow-level and HTTP task timeouts can be expressed in the DSL but do not override the runner’s fixed HTTP limits. State this limitation when a deadline is required.

After a retry budget is exhausted, check how the catch body terminates. When the workflow must fault, include a `catch.do` with an explicit `raise` in the very catch that owns the retry. An empty catch completes after exhaustion, so an outer catch cannot see the exhausted error. Verify the fault and assert that no later success action runs.

[Lemline documentation](../../../../lemline-docs/topics/dsl-timeouts.md)
