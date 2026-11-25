# Resilience Patterns

## Purpose

In distributed systems, failures are inevitable. This page shows how to make workflows handle failures with the tools Lemline provides: retries, error catching with fallbacks, timeouts on event waits, and the durability of the runtime itself.

Lemline does not provide circuit breakers, bulkheads, rate limiting or per-call timeouts, and has no runner settings for them.

## Run the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The examples call
the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop), whose `fail` and `failTimes` query
parameters make a call fail a chosen number of times. Start it from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

## Retry Pattern

### Purpose {id="retry-pattern-purpose"}

Retry an operation automatically when a transient failure occurs.

### Implementation {id="retry-pattern-implementation"}

Retries are declared in the `catch` clause of a `try` task:

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/implementation.yaml"/>

Failed HTTP calls raise a `communication` error whose `status` is the HTTP status. A retry policy can also be declared once in `use.retries` and referenced by name (`retry: <name>`).

Save it as `implementation.yaml`:

```bash
bin/lemline definition post -f implementation.yaml
bin/lemline instance start examples resilience-retry --input '{"failures":2}'
bin/lemline instance start examples resilience-retry --input '{"failures":10}'
```

The first instance fails twice, then returns the stock. The second fails on the call and on all five retries, six
calls in all. The error still matches the `catch`, so it counts as handled: with no `do` list to run, the workflow
continues with the task's input and completes with `{"failures":10}`. Add a `do` list to return a fallback instead,
as in the next pattern, or leave the error uncaught to make the workflow fault. Allow about two minutes for the
second instance, because the delay doubles after each attempt and the worker picks up retries every 10 seconds.

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

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/fallback-pattern.yaml"/>

Save it as `fallback-pattern.yaml`:

```bash
bin/lemline definition post -f fallback-pattern.yaml
bin/lemline instance start examples resilience-fallback --input '{"recommendationsDown":false}'
bin/lemline instance start examples resilience-fallback --input '{"recommendationsDown":true}'
```

Expect `{"recommendations":["SKU-1","SKU-2","SKU-3"]}`, then the default `{"recommendations":[]}` when the shop
answers HTTP 503.

## Timeout Pattern

### Purpose {id="timeout-pattern-purpose"}

Avoid waiting forever.

### Implementation {id="timeout-pattern-implementation"}

- **Event waits**: a `listen` task accepts a `timeout`. When it expires, the task raises a `timeout` error that can be caught.
- **HTTP calls**: the runner's HTTP client stops a request after 30 seconds (15 seconds to connect). This limit is fixed.
- Task-level `timeout` on other tasks and workflow-level timeouts are not applied by Lemline.

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/implementation-2.yaml"/>

The example waits 30 seconds so that the timeout is quick to see; a real payment would get minutes or hours.

Save it as `implementation-2.yaml`:

```bash
bin/lemline definition post -f implementation-2.yaml
bin/lemline instance start examples resilience-listen-timeout
```

Without a payment event, the instance completes with `{"status":"cancelled"}` 30 to 40 seconds later: the worker
checks expired waits every 10 seconds.

## Isolation with Fork

Branches of a `fork` run in parallel. Combine `fork` with `try` in each branch so that the failure of one branch is handled without failing the others:

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/isolation-with-fork.yaml"/>

Save it as `isolation-with-fork.yaml`:

```bash
bin/lemline definition post -f isolation-with-fork.yaml
bin/lemline instance start examples resilience-fork-isolation --input '{"orderId":"ORD-951","items":[{"id":"SKU-1","quantity":1}]}'
bin/lemline instance start examples resilience-fork-isolation --input '{"orderId":"ORD-952","items":[{"id":"SKU-1","quantity":500}]}'
```

Both instances complete. The first returns `{"inventoryUpdated":true}` with the notification. For the second, the shop
refuses the reservation (HTTP 409); its branch returns `{"inventoryUpdated":false}`, and the other branch still sends
the notification: the shop's inbox has an `Order received` entry for both orders.

## Durability of the Runtime

Some resilience comes from the runtime itself, without anything to declare in workflows:

* Workflow state travels in messages and is stored in the database: any runner can continue an instance, and a runner crash does not lose it.
* Messages that a runner cannot process go to a dead-letter destination (`.dlq`) for inspection.
* Runners expose health checks at `/q/health/live` and `/q/health/ready` for orchestrators such as Kubernetes.

## Example: Combined Resilience

<code-block lang="yaml" src="workflows/dsl-resilience-patterns/example-combined-resilience.yaml"/>

This example retries the call with exponential backoff and, once the retries are exhausted, leaves a message in the
order desk's inbox so the order can be processed later. To hand the order to another workflow instead, put an `emit`
task in the `catch` list.

Save it as `example-combined-resilience.yaml`:

```bash
bin/lemline definition post -f example-combined-resilience.yaml
bin/lemline instance start examples resilience-combined --input '{"order":{"orderId":"ORD-953","items":[{"id":"SKU-1","quantity":1}]},"failures":1}'
bin/lemline instance start examples resilience-combined --input '{"order":{"orderId":"ORD-954","items":[{"id":"SKU-1","quantity":1}]},"failures":10}'
```

ORD-953 is reserved after one retry. ORD-954 fails four times; the instance then completes with the order desk entry
`Order queued for later processing`, and the shop has no reservation for it. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
