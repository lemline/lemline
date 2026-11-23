---
title: How to monitor workflow execution
---

# How to monitor workflow execution

This guide explains how to monitor the execution of Lemline workflows, track their progress, view execution details, and set up comprehensive observability for your workflows.

## Understanding Workflow States

A workflow instance is in one of these states:

| State | Description |
|-------|-------------|
| `PENDING` | The instance is created but no runner has started it yet |
| `RUNNING` | The instance is executing, or waiting for an event or a timer |
| `COMPLETED` | The instance finished successfully |
| `FAULTED` | The instance ended with an unhandled error |

## Basic Monitoring Techniques

### Using the Dashboard and the gRPC Gateway

Instances are observed through the gRPC gateway (`lemline gateway start`) and the dashboard built on it. The gateway lets clients:

- get one instance, or list instances filtered by namespace, workflow and status (`GetInstance`, `ListInstances`)
- follow instance changes live (`WatchInstances`)
- list and follow the lifecycle events of an instance (`ListInstanceEvents`, `WatchInstanceEvents`)
- read per-definition counters (`ListDefinitionStats`, `WatchDefinitionStats`)

See [gRPC Gateway Reference](lemline-ref-gateway.md).

The CLI has no command to list or inspect instances. `lemline instance start` prints the id of the instance it starts, which you can then look up in the dashboard.

## Advanced Monitoring

### Listening to Lifecycle Events

Lemline emits lifecycle CloudEvents (`org.open-workflow-specification.workflow.started.v1`, `...task.completed.v1`, and so on) on the lifecycle events channel of your broker. Any consumer of that topic or queue can use them to build real-time monitoring or trigger other processes. With lifecycle analytics enabled, they are also stored in a database table; see [Lifecycle Analytics Reference](lemline-ref-analytics.md).

### Collecting and Analyzing Metrics

Lemline exposes metrics via Micrometer that can be scraped by Prometheus or similar monitoring systems:

```bash
curl http://localhost:8080/q/metrics
```

Lemline metrics describe message processing per channel, with the prefixes `lemline_messaging_commands`, `lemline_messaging_events`, `lemline_messaging_cloudevents` and `lemline_analytics`. For example:

- `lemline_messaging_commands_active`: messages currently being processed
- `lemline_messaging_commands_received_total`: messages received
- `lemline_messaging_commands_processing_completed_total` and `..._processing_failed_total`: messages processed, successfully or not, tagged by workflow name and version
- `lemline_messaging_commands_processing_duration_seconds`: processing time
- `lemline_messaging_commands_foreign_namespace_total`: messages received for a namespace the runner does not serve

JVM, HTTP server and database pool metrics are exposed as well.

These metrics can be visualized in dashboards using tools like Grafana.

### Setting Up Comprehensive Monitoring

For production environments, set up a comprehensive monitoring solution:

1. **Prometheus + Grafana**: For metrics collection and visualization
2. **ELK Stack or Loki**: For centralized log aggregation
3. **Alerting Rules**: Configure alerts for critical conditions:
   - High error rates
   - Long-running workflows
   - Resource constraints
   - Database performance issues

## Debugging Workflow Execution

### Enabling Debug Logging

Start the runner with `--debug`, or set the level of Lemline packages with an environment variable (Quarkus log properties are not read from the configuration file):

```bash
export QUARKUS_LOG_CONSOLE_LEVEL=DEBUG   # the console only shows ERROR by default
export QUARKUS_LOG_CATEGORY__COM_LEMLINE__LEVEL=DEBUG
```

At `DEBUG` level, the runner logs each task it enters with its input, and errors with their details. Every log line carries the workflow namespace, name, version and instance id.

## Monitoring Best Practices

1. **Dashboard for Key Metrics**: Create a dashboard that shows:
   - Active workflow counts by type
   - Success/failure rates
   - Execution times (min, max, average)
   - Resource utilization

2. **Alerting Strategy**: Implement alerts for:
   - Workflows stuck in RUNNING state for too long
   - Abnormal error rates
   - Unusual patterns in workflow behavior
   - Resource constraints that might impact execution

3. **Logging Strategy**: Implement a structured logging pattern:
   - Always include workflow ID and instance ID in logs
   - Log begin/end of significant operations
   - Include correlation IDs for distributed tracing
   - Mask sensitive information in logs

4. **Audit Trail**: Keep the lifecycle events of your instances (for example with lifecycle analytics) and track changes to workflow definitions

5. **Performance Monitoring**: Track and optimize:
   - Database operation counts and durations
   - Message broker interaction patterns
   - External service call performance
   - Resource utilization (CPU, memory)

## Example: Setting Up a Basic Monitoring Dashboard

This example uses Prometheus and Grafana:

1. Configure Prometheus to scrape Lemline metrics:

<!-- workflow-example: data -->
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'lemline'
    metrics_path: '/q/metrics'
    static_configs:
      - targets: ['lemline:8080']
```

2. Create a Grafana dashboard with these panels:
   - Messages processed and failed per channel
   - Message processing time
   - Messages currently in progress
   - System resource utilization

3. Set up alerts for critical conditions:
   - Processing failures increasing
   - Messages received for a foreign namespace
   - Health checks failing at `/q/health/ready`

## Next Steps

- Learn how to [manage workflow lifecycle](lemline-howto-lifecycle.md)
- Explore [how to debug faults in task execution](lemline-howto-debug.md)
- Understand [how to configure observability](lemline-howto-observability.md) in depth