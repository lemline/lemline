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

### Resume an instance event stream

1. Open `WatchInstanceEvents` with the instance ID. Omit `after_cursor` to replay the history before following live events. If the instance was just accepted and the gateway returns `NOT_FOUND`, retry after the first lifecycle event reaches analytics.
2. Save the event's `cursor` after handling each event. If the connection drops, reopen the stream for the same instance with that value in `after_cursor`. The first response after reconnection contains the next event, when available.
3. Close the stream when you have the event you were waiting for. For a finite history query, call `ListInstanceEvents` and follow `next_cursor` until it is absent.

See the [gateway tutorial](lemline-tutorial-gateway.md) for a first history query and the [gateway reference](lemline-ref-gateway.md#list-and-watch-pairing) for cursor behavior across RPCs.

### Following an instance on the SQLite setup

On [the SQLite setup](lemline-tutorial-setup.md), the worker stores every lifecycle event in `analytics.db`. Keep the
setup worker running and use a second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar`
for `bin/lemline`. Save this definition as `hello.yaml`:

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

```bash
bin/lemline definition post -f hello.yaml
bin/lemline instance start examples shop-receipt
```

With the `sqlite3` command-line tool, list the events of that run in order:

```bash
sqlite3 analytics.db "SELECT type FROM lemline_lifecycle_events WHERE lemline_workflow_name = 'shop-receipt' ORDER BY id"
```

The list starts with `org.open-workflow-specification.workflow.created.v1` and `workflow.started.v1`, shows the
`task.created`, `task.started` and `task.completed` events, and ends with `workflow.completed.v1`. Each row's
`payload` column holds the full CloudEvent, including the workflow output.

## Advanced Monitoring

### Listening to Lifecycle Events

Lemline emits lifecycle CloudEvents (`org.open-workflow-specification.workflow.started.v1`, `...task.completed.v1`, and so on) on the lifecycle events channel of your broker. Any consumer of that topic or queue can use them to build real-time monitoring or trigger other processes. With lifecycle analytics enabled, they are also stored in a database table; see [Lifecycle Analytics Reference](lemline-ref-analytics.md).

### Collecting and Analyzing Metrics

Lemline exposes metrics via Micrometer that can be scraped by Prometheus or similar monitoring systems:

```bash
curl http://localhost:8080/q/metrics
```

Use the port given with `--metrics-port` if the worker was started with one.

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

Start the worker in the same terminal after these exports. At `DEBUG` level, the runner logs each task it enters with its input, the workflow output (`Workflow completed with output`), and errors with their details. Every log line carries the workflow namespace, name, version and instance id, for example `[examples/shop-receipt:0.1.0] [01a2128e-…]`. Without `--debug` or these variables, a completed workflow leaves no line in the console.

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
