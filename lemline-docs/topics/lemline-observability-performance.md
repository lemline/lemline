# Performance Observability

This document explains how to measure the performance of Lemline workflows with the metrics and logs Lemline provides, and what to change when it is not sufficient.

## What Lemline Measures

Runners record, for every message they process (see [Metrics Reference](lemline-ref-metrics.md)):

| Metric | Use |
|--------|-----|
| `lemline_messaging_commands_processing_duration_seconds` (by `workflow_name`, `workflow_version`) | Time spent executing workflow steps |
| `lemline_messaging_commands_processing_completed_total` / `_failed_total` | Throughput and failures per workflow |
| `lemline_messaging_commands_active` | Steps in progress on a runner |
| `lemline_messaging_*_deserialization_duration_seconds`, `*_serialization_duration_seconds` | Cost of decoding and encoding messages, which grows with workflow data size |
| `jvm_*`, `process_*` | Memory, garbage collection and CPU of the runner |

There are no per-task or per-instance timing metrics. To see the timeline of a single instance, use the dashboard or the lifecycle events (see [Lifecycle Analytics Reference](lemline-ref-analytics.md)).

## Setting Up Monitoring

Metrics are on by default on `lemline listen` and `lemline gateway start`, at `http://<host>:8080/q/metrics`. Change the port with `lemline.metrics.port` or `--metrics-port`.

Prometheus scrape configuration:

```yaml
scrape_configs:
  - job_name: lemline
    metrics_path: /q/metrics
    static_configs:
      - targets: ['runner-1:8080', 'runner-2:8080']
```

## Useful Queries

```
# Average step duration per workflow
sum by (workflow_name) (rate(lemline_messaging_commands_processing_duration_seconds_sum[5m]))
  / sum by (workflow_name) (rate(lemline_messaging_commands_processing_duration_seconds_count[5m]))

# Steps per second per runner
sum by (instance) (rate(lemline_messaging_commands_processing_completed_total[5m]))

# Slowest step duration seen recently
max by (workflow_name) (lemline_messaging_commands_processing_duration_seconds_max)
```

## Finding Bottlenecks

1. **Compare processing time with end-to-end time.** If steps are fast but instances are slow, messages are waiting in the broker: add runners or partitions.
2. **Look at slow workflows.** A high processing duration usually comes from the remote services called by `call` tasks or from `run` tasks.
3. **Check runner CPU and memory.** High CPU with low concurrency use points at expensive expressions or large data; memory pressure points at large workflow data.
4. **Read the logs of one instance.** Run a runner with `--debug` to log each task with its input. Do not leave it on in production.

## Optimization Techniques

### In workflows

- Keep workflow data small: use `output.as` and `export.as` to keep only what later tasks need. The whole state travels in every message.
- Run independent calls in parallel with `fork`.
- Do not rely on task-level `timeout` to bound remote calls: Lemline only enforces timeouts on `listen` tasks. HTTP calls use fixed client limits instead (15 seconds to connect, 30 seconds per request).
- Use `retry` policies with backoff rather than tight loops.

### In the deployment

- Add runners, and with Kafka add partitions.
- Tune `lemline.messaging.<broker>.<channel>.consumer.concurrency` (default 64).
- Keep `lemline.orchestrator.mode` at `action` (default): `all` sends one message per task, including control-flow tasks, and is meant for testing.
- Place runners close to the broker, the database and the services they call.

## Alerts

```yaml
groups:
  - name: lemline-performance
    rules:
      - alert: LemlineSlowSteps
        expr: |
          sum(rate(lemline_messaging_commands_processing_duration_seconds_sum[5m]))
            / sum(rate(lemline_messaging_commands_processing_duration_seconds_count[5m])) > 2
        for: 10m
        annotations:
          summary: "Average workflow step takes more than 2 seconds"
```

## Related Resources

- [Metrics Reference](lemline-ref-metrics.md)
- [I/O Observability](lemline-observability-io.md)
- [Sizing and Scaling Observability](lemline-observability-sizing.md)
