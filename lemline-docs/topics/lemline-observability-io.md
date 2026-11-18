# I/O Observability

This document explains how to observe the input/output operations of Lemline workflows: calls to remote services, broker messages and database work.

## Types of I/O in Lemline

| I/O | Done by | Where to observe it |
|-----|---------|---------------------|
| Calls to remote services (`call: http`, `openapi`, `grpc`, `asyncapi`) | Runners, during a step | Step duration metrics, runner logs, the remote service's own monitoring |
| Scripts and containers (`run`) | Runners, during a step | Step duration metrics, runner logs |
| Broker messages (commands, events, CloudEvents, lifecycle events) | Runners and gateway | Consumer metrics, broker monitoring |
| Database (definitions, waits, retries, schedules, listeners, failures) | Runners and gateway | Database monitoring |

## Metrics

Lemline records metrics per message consumer, not per remote call (see [Metrics Reference](lemline-ref-metrics.md)):

- `lemline_messaging_commands_processing_duration_seconds`: a step's duration includes the remote calls it makes, so a slow service shows up here, tagged by `workflow_name`.
- `lemline_messaging_<channel>_received_total`, `_processing_failed_total`, `_ack_*`, `_nack_*`: message flow and failures for `commands`, `events` and `cloudevents`.
- `lemline_messaging_<channel>_deserialization_duration_seconds` and `_serialization_duration_seconds`: cost of reading and writing messages, which grows with workflow data size.

Lemline does not export per-endpoint HTTP client metrics, database query metrics or distributed traces.

## Broker Observability

Use the broker's own monitoring for lag and depth:

- **Kafka**: consumer group lag on `<prefix>-<namespace>-commands`, `-events` and `-cloudevents` topics (consumer group `lemline-group` by default). Failed records go to `<topic>.dlq`.
- **RabbitMQ**: queue depth and unacknowledged messages.
- **PGMQ**: queue tables in the PostgreSQL database.

Messages a runner cannot process for its namespaces are stored as failures and can be inspected or re-sent with `lemline failures replay --dry-run`.

## Logs

Runner logs carry the workflow namespace, name, version and instance id. Raise the level to see I/O details:

```bash
lemline listen --debug
```

Or, for Lemline packages only, in the configuration file:

```yaml
quarkus:
  log:
    category:
      "com.lemline":
        level: DEBUG
```

At `DEBUG`, task inputs are logged: keep it off in production.

## Making I/O Reliable

I/O behavior is controlled in the workflow definition:

- `timeout` on a task limits how long a remote call may take.
- `try` with a `retry` policy retries failed calls with a delay and backoff; retries are scheduled through the database, so a waiting retry holds no runner resources.
- `fork` runs independent calls in parallel.
- `output.as` and `export.as` keep workflow data small, which reduces the size of every message.

## Alerts

```yaml
groups:
  - name: lemline-io
    rules:
      - alert: LemlineMessageFailures
        expr: sum by (reason) (rate(lemline_messaging_commands_processing_failed_total[5m])) > 0
        for: 5m
        annotations:
          summary: "Workflow steps are failing ({{ $labels.reason }})"
      - alert: LemlineDeserializationFailures
        expr: sum(rate(lemline_messaging_commands_deserialization_failed_total[5m])) > 0
        for: 5m
        annotations:
          summary: "Runners receive messages they cannot decode"
```

## Related Resources

- [Metrics Reference](lemline-ref-metrics.md)
- [Performance Observability](lemline-observability-performance.md)
- [Lifecycle Observability](lemline-observability-lifecycle.md)
