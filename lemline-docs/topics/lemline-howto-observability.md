# How to Configure Observability

This guide explains how to observe Lemline in operation: metrics, health checks, logs, lifecycle events and the dashboard.

## When to Configure Observability

Set up observability when you:

- run Lemline in a shared or production environment
- need to know whether runners are healthy and keeping up
- investigate failed or slow workflows
- keep a history of workflow instances

## Observability Components

| Component | Where | Configuration |
|-----------|-------|---------------|
| Metrics | `http://<host>:8080/q/metrics` (Prometheus format) | `lemline.metrics.port`, `lemline.metrics.path` |
| Health checks | `/q/health`, `/q/health/live`, `/q/health/ready` on the same port | none |
| Logs | standard output | `--debug`/`--info`/`--warn`/`--error`, `--json`, Quarkus log properties |
| Lifecycle events | lifecycle events topic or queue | messaging configuration |
| Lifecycle analytics | analytics database table | `lemline.analytics.*` |
| Dashboard | through the gRPC gateway | `lemline.gateway.*` |

Lemline does not include distributed tracing (OpenTelemetry) or alerting; use your monitoring stack for alerts.

## Metrics

`lemline listen` and `lemline gateway start` serve Prometheus metrics. Change the port with `lemline.metrics.port` or `--metrics-port`, and the path with `lemline.metrics.path`:

```yaml
lemline:
  metrics:
    port: 9090
    path: /q/metrics
```

Lemline metrics describe message processing per channel, with the prefixes `lemline_messaging_commands`, `lemline_messaging_events`, `lemline_messaging_cloudevents` and `lemline_analytics`:

| Metric (suffix) | Type | Description |
|-----------------|------|-------------|
| `_active` | Gauge | Messages currently being processed |
| `_received_total` | Counter | Messages received |
| `_deserialization_completed_total`, `_deserialization_failed_total` | Counter | Messages decoded, or rejected |
| `_deserialization_duration_seconds` | Timer | Decoding time |
| `_processing_completed_total`, `_processing_failed_total` | Counter | Messages processed, or failed (tagged with `reason`) |
| `_processing_duration_seconds` | Timer | Processing time |
| `_serialization_completed_total`, `_serialization_failed_total` | Counter | Outgoing messages encoded, or failed |
| `_serialization_duration_seconds` | Timer | Encoding time |
| `_ack_completed_total`, `_ack_failed_total`, `_nack_completed_total`, `_nack_failed_total` | Counter | Broker acknowledgements |
| `_foreign_namespace_total` | Counter | Messages received for a namespace the runner does not serve |

Most counters are tagged with `workflow_name` and `workflow_version`. The gateway adds `lemline_gateway_start_inactive_namespace_total`, counted when an instance is started in a namespace with no recent runner activity. JVM, HTTP server and connection pool metrics from Quarkus are exposed as well.

### Prometheus Integration

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'lemline'
    metrics_path: '/q/metrics'
    static_configs:
      - targets: ['lemline-runner:8080']
```

## Health Checks

Runners expose SmallRye Health endpoints on the metrics port:

- `/q/health/live`: liveness (`worker-liveness`)
- `/q/health/ready`: readiness (`worker-readiness`)
- `/q/health`: both

Use them as Kubernetes liveness and readiness probes.

## Logging

### Log Levels

The default level is `ERROR`. Set another level for any command:

```bash
lemline listen --info
lemline listen --debug
```

At `DEBUG` level, the runner logs each task it enters with its input, and the workflow output.

### Log Categories

For finer control, set Quarkus log properties in the configuration file:

```yaml
quarkus:
  log:
    level: INFO
    category:
      "com.lemline":
        level: INFO
      "com.lemline.core.processors":
        level: DEBUG
```

### Structured Logging

Use `--json` to write logs as JSON, for ingestion by Loki, Elasticsearch or similar tools:

```bash
lemline listen --info --json
```

Every log line carries the workflow namespace, name, version and instance id, so you can filter the logs of one instance.

## Lifecycle Events

Runners publish a CloudEvent for each workflow and task transition (`org.open-workflow-specification.workflow.started.v1`, `...task.completed.v1`, `...workflow.faulted.v1`, and so on) on the lifecycle events channel. Any consumer can read them from the broker. See [Workflow Lifecycle Observability](lemline-observability-lifecycle.md).

## Lifecycle Analytics

To keep a queryable history, enable lifecycle analytics: a runner consumes the lifecycle events and stores them in a PostgreSQL (or in-memory H2) table. The dashboard uses this data. See [Lifecycle Analytics Reference](lemline-ref-analytics.md).

## Dashboard

The dashboard reads definitions, instances, lifecycle events and per-definition counters through the gRPC gateway. Start the gateway with `lemline gateway start`; see [gRPC Gateway Reference](lemline-ref-gateway.md).

## Common Issues and Solutions

### Missing Metrics

- Metrics are served only by long-running commands (`listen`, `gateway start`); other commands disable the endpoint.
- Check the port: `lemline.metrics.port` (default `8080`) or `--metrics-port`.
- Per-workflow counters appear only after the runner has processed a message for that workflow.

### Log Volume Problems

- Keep production runners at `INFO` or above; `DEBUG` logs task inputs.
- Raise the level of noisy categories only, using `quarkus.log.category`.

## Best Practices

1. **Scrape every runner and gateway** with Prometheus
2. **Use the health endpoints** as liveness and readiness probes
3. **Use JSON logs** in production, and filter by instance id when investigating
4. **Alert on `_processing_failed_total` and `_foreign_namespace_total`**
5. **Enable lifecycle analytics** where you need an instance history
6. **Do not log sensitive data**: avoid `DEBUG` level in production

## Related Information

- [Performance Monitoring](lemline-observability-performance.md)
- [Workflow Lifecycle Observability](lemline-observability-lifecycle.md)
- [I/O Observability](lemline-observability-io.md)
- [Runner Configuration Reference](lemline-ref-config.md)
