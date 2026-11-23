# Workflow Lifecycle Observability

This document explains how to follow workflow instances from creation to completion in Lemline: which lifecycle events are emitted, where to see them, and how to query them.

## Workflow Lifecycle Phases

A workflow goes through these phases:

1. **Definition**: the definition is registered with `lemline definition post`
2. **Creation**: an instance is created (`lemline instance start`, the gRPC gateway, a schedule or a parent workflow)
3. **Execution**: the instance runs its tasks; it may wait for events or timers
4. **Completion**: the instance ends as `COMPLETED` or `FAULTED`

## Core Observability Events

Lemline emits a CloudEvent for each lifecycle transition, following the Open Workflow specification:

| Event type | Emitted when | Main data |
|------------|--------------|-----------|
| `org.open-workflow-specification.workflow.created.v1` | An instance is created | workflow name, input, definition reference |
| `org.open-workflow-specification.workflow.started.v1` | An instance starts | workflow name, definition reference |
| `org.open-workflow-specification.workflow.completed.v1` | An instance completes | workflow name, output |
| `org.open-workflow-specification.workflow.faulted.v1` | An instance faults | workflow name, error |
| `org.open-workflow-specification.task.created.v1` | A task is created | workflow, task, input |
| `org.open-workflow-specification.task.started.v1` | A task starts | workflow, task |
| `org.open-workflow-specification.task.completed.v1` | A task completes | workflow, task, output |
| `org.open-workflow-specification.task.faulted.v1` | A task faults | workflow, task, error |
| `org.open-workflow-specification.task.retried.v1` | A task is retried | workflow, task, retry count |

Errors carry the standard fields `type`, `status`, `title`, `instance` and `detail`.

The events are published on the lifecycle events channel (by default the `lemline-lifecycle-events` topic or queue). See [Lifecycle Events](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl-reference.md#lifecycle-events).

## Monitoring Workflow Definitions

List and read the registered definitions from the CLI:

```bash
# List all workflow definitions
lemline definition get

# Print one definition
lemline definition get example order-processing 1.0.0
```

The gRPC gateway also exposes per-definition counters (`ListDefinitionStats`, `WatchDefinitionStats`), shown in the dashboard.

## Monitoring Workflow Instances

The CLI does not list or inspect instances. Use the dashboard, or the gRPC gateway directly:

- `ListInstances` / `GetInstance`: find instances by namespace, workflow, version, status and creation time
- `WatchInstances`: follow instance changes live
- `ListInstanceEvents` / `WatchInstanceEvents`: read or follow the lifecycle events of one instance

See [gRPC Gateway Reference](lemline-ref-gateway.md).

## Storing Lifecycle Events

With lifecycle analytics enabled, a runner stores the complete CloudEvent in the `payload` column of `lemline_lifecycle_events`. Dedicated columns hold the workflow ID, namespace, name and version. See [Lifecycle Analytics Reference](lemline-ref-analytics.md) to configure SQLite or PostgreSQL.

Run the following queries on PostgreSQL. Replace the `lemline` schema qualifier if you changed `lemline.analytics.postgresql.schema`.

```sql
-- Complete lifecycle of one instance
SELECT type, event_time, payload
FROM lemline.lemline_lifecycle_events
WHERE lemline_workflow_id = '01890a5d-ac96-774b-bcce-b302099a8057'
ORDER BY event_time;

-- Faulted instances per workflow over the last day
SELECT lemline_workflow_name, lemline_workflow_version, COUNT(*) AS faulted
FROM lemline.lemline_lifecycle_events
WHERE type = 'org.open-workflow-specification.workflow.faulted.v1'
  AND event_time > NOW() - INTERVAL '1 day'
GROUP BY lemline_workflow_name, lemline_workflow_version
ORDER BY faulted DESC;
```

For SQLite, open the configured file with `sqlite3 -readonly /var/lib/lemline/analytics.db`. Query the unqualified table and order by `id` to read events in the same order as the gateway history. SQLite returns `event_time` in Unix epoch microseconds.

```sql
SELECT type, event_time, payload
FROM lemline_lifecycle_events
WHERE lemline_workflow_id = '01890a5d-ac96-774b-bcce-b302099a8057'
ORDER BY id;
```

## Metrics

Runners expose Prometheus metrics at `/q/metrics`. They count and time message processing per channel (for example `lemline_messaging_commands_processing_completed_total`, `lemline_messaging_commands_processing_failed_total` and `lemline_messaging_commands_processing_duration_seconds`), tagged by workflow name and version. Lemline does not publish per-instance or per-task duration metrics; derive those from the lifecycle events.

These counters track message handling. A workflow that faults (an HTTP call returning 500, a `raise`…) is processed successfully from the runner's point of view and does not increase `processing_failed_total`: alert on faulted workflows with the `workflow.faulted` lifecycle events instead.

Example Prometheus alert on message-processing failures (broker, database or infrastructure problems):

<!-- workflow-example: data -->
```yaml
groups:
- name: lemline-alerts
  rules:
  - alert: LemlineProcessingFailures
    expr: sum(increase(lemline_messaging_commands_processing_failed_total[15m])) > 0
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Lemline runners failed to process workflow messages"
```

## Best Practices for Workflow Lifecycle Observability

1. **Enable lifecycle analytics** in environments where you need an instance history
2. **Alert on faulted workflows** using the `workflow.faulted` events; use processing failure metrics for message-handling and infrastructure failures
3. **Correlate logs with instances**: every runner log line carries the workflow namespace, name, version and instance id
4. **Define a retention policy** for the analytics table

For more details on specific types of observability data, see:
- [Performance Observability](lemline-observability-performance.md)
- [I/O Observability](lemline-observability-io.md)
