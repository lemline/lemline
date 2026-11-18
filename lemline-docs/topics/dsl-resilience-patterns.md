# Resilience Patterns

## Purpose

In distributed systems, failures are inevitable. This page shows how to make workflows handle failures with the tools Lemline provides: retries, error catching with fallbacks, timeouts on event waits, and the durability of the runtime itself.

Lemline does not provide circuit breakers, bulkheads, rate limiting or per-call timeouts, and has no runner settings for them.

## Retry Pattern

### Purpose

Retry an operation automatically when a transient failure occurs.

### Implementation

Retries are declared in the `catch` clause of a `try` task:

```yaml
- callService:
    try:
      - request:
          call: http
          with:
            method: get
            endpoint: http://example.com/api
    catch:
      errors:
        with:
          type: https://open-workflow-specification.org/spec/1.0.0/errors/runtime
      retry:
        delay:
          seconds: 2
        backoff:
          exponential: {}
        jitter:
          from:
            milliseconds: 0
          to:
            milliseconds: 500
        limit:
          attempt:
            count: 5
          duration:
            minutes: 5
```

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

```yaml
- getRecommendations:
    try:
      - request:
          call: http
          with:
            method: get
            endpoint: http://recommendations/api
    catch:
      as: error
      do:
        - useDefault:
            set:
              recommendations: []
```

## Timeout Pattern

### Purpose

Avoid waiting forever.

### Implementation

- **Event waits**: a `listen` task accepts a `timeout`. When it expires, the task raises a `timeout` error that can be caught.
- **HTTP calls**: the runner's HTTP client stops a request after 30 seconds (15 seconds to connect). This limit is fixed.
- Task-level `timeout` on other tasks and workflow-level timeouts are not applied by Lemline.

```yaml
- waitForPayment:
    try:
      - listenPayment:
          listen:
            to:
              one:
                with:
                  type: com.example.payment.received
          timeout:
            after:
              minutes: 30
    catch:
      errors:
        with:
          type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
      do:
        - cancelOrder:
            set:
              status: cancelled
```

## Isolation with Fork

Branches of a `fork` run in parallel. Combine `fork` with `try` in each branch so that the failure of one branch is handled without failing the others:

```yaml
- processBatch:
    fork:
      branches:
        - updateInventory:
            try:
              - reserveStock:
                  call: http
                  with:
                    method: post
                    endpoint: http://inventory/api/reservations
                    body: ${ .items }
            catch:
              do:
                - ignore:
                    set:
                      inventoryUpdated: false
        - notifyCustomer:
            emit:
              event:
                with:
                  type: com.example.order.confirmed
                  source: order-service
```

## Durability of the Runtime

Some resilience comes from the runtime itself, without anything to declare in workflows:

* Workflow state travels in messages and is stored in the database: any runner can continue an instance, and a runner crash does not lose it.
* Messages that a runner cannot process go to a dead-letter destination (`.dlq`) for inspection.
* Runners expose health checks at `/q/health/live` and `/q/health/ready` for orchestrators such as Kubernetes.

## Example: Combined Resilience

```yaml
- processOrder:
    try:
      - submitOrder:
          call: http
          with:
            method: post
            endpoint: http://order-service/api/orders
            body: ${ .order }
    catch:
      errors:
        with:
          type: https://open-workflow-specification.org/spec/1.0.0/errors/runtime
      retry:
        delay:
          seconds: 2
        backoff:
          exponential: {}
        limit:
          attempt:
            count: 3
      do:
        - queueForLater:
            emit:
              event:
                with:
                  type: com.example.order.pending
                  source: order-workflow
                  data: ${ .order }
```

This example retries the call with exponential backoff and, once the retries are exhausted, emits an event so the order can be processed later.
