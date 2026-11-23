# Understanding Time Handling in Workflows

Use a duration for an elapsed interval and a timestamp for a point in time. For example, `PT30S` means 30 seconds, while `2026-04-30T17:00:00Z` identifies an instant in UTC. Include an offset or `Z` when exchanging timestamps between services.

## Waiting and deadlines

A `wait` suspends the workflow for a duration. A `timeout` limits how long a task or the whole workflow may take. This definition declares a five-minute workflow deadline and a ten-second HTTP task deadline. Lemline currently enforces listener timeouts. The workflow and HTTP task deadlines illustrate schema syntax. HTTP requests use the client timeout.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: delayed-status-check
  version: '1.0.0'
timeout: { after: PT5M }
do:
  - pause:
      wait: PT30S
  - getStatus:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/status
      timeout: { after: PT10S }
```

To wait until a UTC deadline supplied as input, compute the remaining duration. Clamp it to zero so an overdue workflow proceeds immediately. This expression expects a UTC timestamp of the form `YYYY-MM-DDThh:mm:ssZ`.

```yaml
- waitUntilDeadline:
    wait: '${ {seconds: ([0, ((.deadline | fromdateiso8601) - now | ceil)] | max)} }'
```

## Waiting for an event

The workflow can continue as soon as a matching event arrives. A timeout on the listener bounds the wait even if the producer stops sending events.

```yaml
- waitForDecision:
    listen:
      to:
        any:
          - with: { type: OrderApproved }
          - with: { type: OrderRejected }
    timeout: { after: PT24H }
```

## Retrying after a delay

Place the retry policy under `catch.retry`. The following task starts with a one-second delay, uses exponential backoff, and limits retries to three attempts for HTTP 503 responses.

```yaml
- fetchData:
    try:
      - request:
          call: http
          with:
            method: get
            endpoint: https://api.example.com/data
    catch:
      errors: { with: { status: 503 } }
      retry:
        delay: PT1S
        backoff: { exponential: {} }
        limit: { attempt: { count: 3 } }
```

## Schedules and time windows

Use a workflow-level schedule to start new instances periodically. For a query that covers one hour, compute both bounds from the same timestamp. This keeps the interval exactly one hour even if the query takes time.

```yaml
- buildWindow:
    set: '${ now as $end | {start: (($end - 3600) | todateiso8601), end: ($end | todateiso8601)} }'
- fetchWindow:
    call: http
    with:
      method: get
      endpoint: https://api.example.com/data
      query:
        startTime: ${ .start }
        endTime: ${ .end }
```

A per-instance delay can space requests from one workflow. To enforce a shared rate limit, coordinate through a rate-limiting service. For debouncing, have the event producer or a stateful consumer track the last activity for each key and emit a completion event when the quiet period expires.

See [Wait](dsl-task-wait.md), [Timeouts](dsl-timeouts.md) and [Workflow Definition](dsl-workflow-definition.md).
