# Metrics Reference

This reference lists the metrics Lemline exposes and where to read them.

## Endpoint

`lemline listen` and `lemline gateway start` serve Prometheus metrics over HTTP:

| Item | Value |
|------|-------|
| URL | `http://<host>:<port>/q/metrics` |
| Port | `lemline.metrics.port` (default `8080`), or `--metrics-port` |
| Path | `lemline.metrics.path` (default `/q/metrics`) |

Health checks are served on the same port: `/q/health`, `/q/health/live` and `/q/health/ready`.

Other commands (`definition`, `instance start`, `config`, `migrate`, `failures replay`) do not expose metrics.

```bash
curl http://localhost:8080/q/metrics
```

## Naming

Metrics are recorded with Micrometer and exported in Prometheus format. Dots become underscores, and timers are exported as `_seconds_count`, `_seconds_sum` and `_seconds_max`. For example:

| Micrometer name | Prometheus name |
|-----------------|-----------------|
| `lemline.messaging.commands.received.total` | `lemline_messaging_commands_received_total` |
| `lemline.messaging.commands.processing.duration` | `lemline_messaging_commands_processing_duration_seconds_*` |

## Message Consumer Metrics

Each consumer of a runner records the same set of metrics under its own prefix:

| Prefix | Consumer |
|--------|----------|
| `lemline.messaging.commands` | Workflow commands (workflow execution steps) |
| `lemline.messaging.events` | Workflow events (durable operations stored by the runner) |
| `lemline.messaging.cloudevents` | CloudEvents consumed for `listen` tasks |
| `lemline.analytics` | Lifecycle events ingested by lifecycle analytics |

For each `<prefix>`:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `<prefix>.active` | Gauge | - | Messages being processed right now |
| `<prefix>.received.total` | Counter | - | Messages received |
| `<prefix>.deserialization.completed.total` | Counter | `workflow_name`, `workflow_version` | Messages decoded |
| `<prefix>.deserialization.failed.total` | Counter | `reason` | Messages that could not be decoded |
| `<prefix>.deserialization.duration` | Timer | - | Time spent decoding |
| `<prefix>.serialization.completed.total` | Counter | `workflow_name`, `workflow_version` | Messages encoded |
| `<prefix>.serialization.failed.total` | Counter | `reason`, `workflow_name`, `workflow_version` | Messages that could not be encoded |
| `<prefix>.serialization.duration` | Timer | - | Time spent encoding |
| `<prefix>.processing.completed.total` | Counter | `workflow_name`, `workflow_version` | Messages processed successfully |
| `<prefix>.processing.failed.total` | Counter | `reason`, `workflow_name`, `workflow_version` | Messages whose processing failed |
| `<prefix>.processing.duration` | Timer | `workflow_name`, `workflow_version` | Processing time, successful or not |
| `<prefix>.ack.completed.total` | Counter | `workflow_name`, `workflow_version` | Messages acknowledged to the broker |
| `<prefix>.ack.failed.total` | Counter | `reason`, `workflow_name`, `workflow_version` | Acknowledgements that failed |
| `<prefix>.nack.completed.total` | Counter | `workflow_name`, `workflow_version` | Messages rejected to the broker |
| `<prefix>.nack.failed.total` | Counter | `reason`, `workflow_name`, `workflow_version` | Rejections that failed |
| `<prefix>.foreign_namespace.total` | Counter | `workflow_namespace`, `workflow_name`, `workflow_version` | Messages received for a namespace this runner does not serve |

A rising `foreign_namespace.total` means messages reach a runner that is not configured for their namespace (see `lemline.namespaces`). Those messages are parked as failures and can be re-sent with `lemline failures replay`.

## Gateway Metrics

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `lemline.gateway.start.inactive_namespace.total` | Counter | `namespace` | Instances started in a namespace where no runner activity was recorded recently |

## Platform Metrics

Micrometer also exports its standard JVM and process metrics (memory, garbage collection, threads, CPU, uptime), with names such as `jvm_memory_used_bytes` and `process_cpu_usage`.

## Example Queries

```
# Commands processed per second, by workflow
sum by (workflow_name) (rate(lemline_messaging_commands_processing_completed_total[5m]))

# Failure ratio of command processing
sum(rate(lemline_messaging_commands_processing_failed_total[5m]))
  / sum(rate(lemline_messaging_commands_received_total[5m]))

# Average processing time
rate(lemline_messaging_commands_processing_duration_seconds_sum[5m])
  / rate(lemline_messaging_commands_processing_duration_seconds_count[5m])

# Messages in flight per runner
lemline_messaging_commands_active
```

## Related Resources

- [Runner Configuration Reference](lemline-ref-config.md)
- [Understanding Scaling in Lemline](lemline-explain-scaling.md)
