# Sizing and Scaling Observability

This document explains which signals to watch to size a Lemline deployment and decide when to add capacity. Lemline has no built-in autoscaler or sizing tool: it exposes metrics, and you scale with your platform (processes, Kubernetes replicas, broker partitions, database).

## What Uses Resources

| Component | Main resources | Grows with |
|-----------|----------------|------------|
| Runner (`lemline listen`) | CPU, memory, broker and database connections | Steps executed per second, consumer concurrency, size of workflow data |
| Gateway (`lemline gateway start`) | CPU, database connections | Start requests, open watch streams |
| Message broker | Disk, network | Messages per second, retention |
| Database | CPU, I/O, storage | Waits, retries, schedules, listeners, definitions, failures, analytics |

Workflow data travels inside every message of an instance, so large inputs and outputs increase broker traffic and runner memory.

## Signals to Watch

All Lemline metrics are listed in the [Metrics Reference](lemline-ref-metrics.md).

| Question | Signal |
|----------|--------|
| Is a runner saturated? | `lemline_messaging_commands_active` close to `lemline.messaging.<broker>.commands.consumer.concurrency` (default 64); high `process_cpu_usage` |
| Are runners keeping up? | Broker consumer lag (Kafka) or queue depth (RabbitMQ, PGMQ), from the broker's own monitoring |
| Are steps getting slower? | `lemline_messaging_commands_processing_duration_seconds` |
| Is memory sufficient? | `jvm_memory_used_bytes` against the container limit; garbage collection time |
| Are messages failing? | `lemline_messaging_*_processing_failed_total` and `*_deserialization_failed_total` |
| Are runners missing for a namespace? | `lemline_messaging_*_foreign_namespace_total`, `lemline_gateway_start_inactive_namespace_total` |

The database and the broker are shared by every runner: watch them with their own tools.

## Sizing Guidelines

- Start with two runners for availability, then add runners while lag grows.
- With Kafka, create topics with at least as many partitions as the maximum number of runners.
- Raise `lemline.messaging.<broker>.<channel>.consumer.concurrency` when runners mostly wait on remote calls and CPU stays low; lower it when memory runs short.
- Size the database for the number of runners and gateways connected to it, and for the background processing (waits, retries, schedules) that every runner performs.

## Scaling

See [How to Configure Scaling](lemline-howto-scaling.md) for adding runners, splitting work by namespace and using a Kubernetes Horizontal Pod Autoscaler.

## Alerts

Example Prometheus rules based on real metrics:

<!-- workflow-example: data -->
```yaml
groups:
  - name: lemline-sizing
    rules:
      - alert: LemlineRunnerSaturated
        expr: avg_over_time(lemline_messaging_commands_active[10m]) > 56
        for: 10m
        annotations:
          summary: "Runner close to its commands concurrency limit (64)"
      - alert: LemlineCommandFailures
        expr: sum(rate(lemline_messaging_commands_processing_failed_total[5m])) > 1
        for: 5m
        annotations:
          summary: "Workflow steps are failing"
```

Adjust the saturation threshold if you changed the concurrency.

## Related Resources

- [Metrics Reference](lemline-ref-metrics.md)
- [Understanding Scaling in Lemline](lemline-explain-scaling.md)
- [How to Configure Scaling](lemline-howto-scaling.md)
