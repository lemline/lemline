# Resilience Patterns

## Purpose

In distributed systems, failures are inevitable. This page shows how to make workflows handle failures with the tools Lemline provides: retries, error catching with fallbacks, timeouts on event waits, and the durability of the runtime itself.

Lemline does not provide circuit breakers, bulkheads, rate limiting or per-call timeouts, and has no runner settings for them.

## Retry Pattern

### Purpose

Retry an operation automatically when a transient failure occurs.

### Implementation

Retries are declared in the `catch` clause of a `try` task:

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/implementation.yaml" include-lines="7-32"/>

Failed HTTP calls raise a `runtime` error. A retry policy can also be declared once in `use.retries` and referenced by name (`retry: <name>`).

### Key Components

* **Delay** (`delay`): time to wait before retrying
* **Backoff** (`backoff`): `constant`, `linear` (the delay grows with each attempt) or `exponential`
* **Jitter** (`jitter`): random extra delay between `from` and `to`
* **Limits** (`limit.attempt.count`, `limit.duration`): maximum attempts and maximum total duration
* **Conditions** (`when`, `exceptWhen`): retry only when an expression holds

While waiting for a retry, the instance is stored in the database: runners can stop or restart without losing it.

### Best Practices

1. **Use backoff**: start with short delays and increase them
2. **Add jitter**: avoid many instances retrying at the same moment
3. **Set limits**: never retry indefinitely
4. **Be selective**: only retry errors that are likely to be transient
5. **Make calls idempotent**: a retried call may have reached the service the first time

## Fallback Pattern

Catch an error and continue with a default value or an alternative path:

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/fallback-pattern.yaml" include-lines="7-19"/>

## Timeout Pattern

### Purpose

Avoid waiting forever.

### Implementation

- **Event waits**: a `listen` task accepts a `timeout`. When it expires, the task raises a `timeout` error that can be caught.
- **HTTP calls**: the runner's HTTP client stops a request after 30 seconds (15 seconds to connect). This limit is fixed.
- Task-level `timeout` on other tasks and workflow-level timeouts are not applied by Lemline.

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/implementation-2.yaml" include-lines="7-25"/>

## Isolation with Fork

Branches of a `fork` run in parallel. Combine `fork` with `try` in each branch so that the failure of one branch is handled without failing the others:

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/isolation-with-fork.yaml" include-lines="7-28"/>

## Durability of the Runtime

Some resilience comes from the runtime itself, without anything to declare in workflows:

* Workflow state travels in messages and is stored in the database: any runner can continue an instance, and a runner crash does not lose it.
* Messages that a runner cannot process go to a dead-letter destination (`.dlq`) for inspection.
* Runners expose health checks at `/q/health/live` and `/q/health/ready` for orchestrators such as Kubernetes.

## Example: Combined Resilience

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/example-combined-resilience.yaml" include-lines="7-34"/>

This example retries the call with exponential backoff and, once the retries are exhausted, emits an event so the order can be processed later.
