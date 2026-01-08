# Quickstart: Workflow Lifecycle Events

**Feature**: 002-lifecycle-events

## Overview

Lifecycle events are CloudEvents published to a dedicated channel (`lemline-lifecycle-events`) in real-time as workflows and tasks execute. Use them for monitoring, debugging, alerting, and audit logging.

## Configuration

### Enable Lifecycle Events (Default: Enabled)

```yaml
# application.yml
lemline:
  messaging:
    lifecycleevents:
      producer:
        enabled: true  # Emit lifecycle events
```

### Kafka Configuration

```yaml
lemline:
  messaging:
    type: kafka
    kafka:
      brokers: localhost:9092
      lifecycleevents:
        topic: lemline-lifecycle-events
```

### RabbitMQ Configuration

```yaml
lemline:
  messaging:
    type: rabbitmq
    rabbitmq:
      host: localhost
      port: 5672
      lifecycleevents:
        exchange: lemline-lifecycle-events
```

## Consuming Lifecycle Events

### Kafka Consumer (CLI)

```bash
# View lifecycle events in real-time
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic lemline-lifecycle-events \
  --from-beginning
```

### Example Event: Workflow Started

```json
{
  "specversion": "1.0",
  "id": "0192d4e5-7a8b-7c00-8d1e-f2a3b4c5d6e7",
  "source": "urn:lemline:workflow:default:order-processing:1.0.0",
  "type": "io.serverlessworkflow.workflow.started.v1",
  "time": "2025-12-08T10:30:00.000Z",
  "datacontenttype": "application/json",
  "lemlineworkflowid": "0192d4e5-7a8b-7c00-8d1e-f2a3b4c5d6e8",
  "lemlineworkflownamespace": "default",
  "lemlineworkflowname": "order-processing",
  "lemlineworkflowversion": "1.0.0",
  "data": {
    "name": "default/order-processing:1.0.0",
    "startedAt": "2025-12-08T10:30:00.000Z",
    "definition": {
      "namespace": "default",
      "name": "order-processing",
      "version": "1.0.0"
    }
  }
}
```

### Example Event: Task Completed

```json
{
  "specversion": "1.0",
  "id": "0192d4e5-7a8b-7c00-8d1e-f2a3b4c5d6e9",
  "source": "urn:lemline:workflow:default:order-processing:1.0.0",
  "type": "io.serverlessworkflow.task.completed.v1",
  "time": "2025-12-08T10:30:05.000Z",
  "datacontenttype": "application/json",
  "lemlineworkflowid": "0192d4e5-7a8b-7c00-8d1e-f2a3b4c5d6e8",
  "lemlineworkflownamespace": "default",
  "lemlineworkflowname": "order-processing",
  "lemlineworkflowversion": "1.0.0",
  "data": {
    "workflow": "default/order-processing:1.0.0",
    "task": "/do/0/validateOrder",
    "completedAt": "2025-12-08T10:30:05.000Z"
  }
}
```

## Event Types Reference

| Event Type | When Emitted |
|------------|--------------|
| `io.serverlessworkflow.workflow.started.v1` | Workflow begins |
| `io.serverlessworkflow.workflow.completed.v1` | Workflow succeeds |
| `io.serverlessworkflow.workflow.faulted.v1` | Workflow fails |
| `io.serverlessworkflow.task.started.v1` | Task begins |
| `io.serverlessworkflow.task.completed.v1` | Task succeeds |
| `io.serverlessworkflow.task.faulted.v1` | Task fails |
| `io.serverlessworkflow.task.retried.v1` | Task retry begins |

## Correlation

All events for a workflow execution share the same `lemlineworkflowid` extension attribute. Use this to correlate events:

```sql
-- Example: Find all events for a workflow instance
SELECT * FROM lifecycle_events
WHERE JSON_EXTRACT(event, '$.lemlineworkflowid') = '0192d4e5-7a8b-7c00-8d1e-f2a3b4c5d6e8'
ORDER BY JSON_EXTRACT(event, '$.time');
```

## Use Cases

### Monitoring Dashboard

Subscribe to `workflow.completed` and `workflow.faulted` events to track:
- Workflow success/failure rates
- Average execution times
- Error trends

### Alerting

Subscribe to `workflow.faulted` and `task.faulted` events to trigger alerts:
- PagerDuty notifications
- Slack messages
- Email alerts

### Audit Logging

Store all lifecycle events for compliance:
- Who ran what workflow
- When did it run
- What was the outcome

### Debugging

Use task-level events to trace execution:
- Which task is currently running
- How long did each task take
- Where did the workflow fail

## Disabling Lifecycle Events

For high-throughput scenarios where observability overhead is unacceptable:

```yaml
lemline:
  messaging:
    lifecycleevents:
      producer:
        enabled: false
```

Or via environment variable:

```bash
export LEMLINE_MESSAGING_LIFECYCLEEVENTS_PRODUCER_ENABLED=false
```
