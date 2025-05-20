# Metrics Reference

This reference documents all metrics exposed by the Lemline runner.

## Metrics Overview

Lemline provides detailed metrics for monitoring workflow execution, system health, and performance. These metrics are exposed through:

1. **Prometheus endpoint**: Available at `/metrics` by default
2. **JMX**: When enabled with `lemline.metrics.jmx.enabled=true`
3. **Micrometer Registry**: For programmatic access or integration with other monitoring systems

All metrics use the prefix `lemline` by default (configurable with `lemline.metrics.prefix`).

## Workflow Metrics

### Workflow Execution Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.workflow.instances.started` | Counter | Number of workflow instances started | `workflowId`, `version` |
| `lemline.workflow.instances.completed` | Counter | Number of workflow instances completed successfully | `workflowId`, `version` |
| `lemline.workflow.instances.failed` | Counter | Number of workflow instances that failed | `workflowId`, `version`, `errorType` |
| `lemline.workflow.instances.duration` | Timer | Workflow execution duration | `workflowId`, `version` |
| `lemline.workflow.instances.active` | Gauge | Number of currently active workflow instances | `workflowId`, `version` |
| `lemline.workflow.instances.suspended` | Gauge | Number of currently suspended workflow instances | `workflowId`, `version` |

### Task Execution Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.task.executions` | Counter | Number of task executions | `workflowId`, `taskType`, `taskName` |
| `lemline.task.execution.duration` | Timer | Task execution duration | `workflowId`, `taskType`, `taskName` |
| `lemline.task.failures` | Counter | Number of task failures | `workflowId`, `taskType`, `taskName`, `errorType` |
| `lemline.task.retries` | Counter | Number of task retries | `workflowId`, `taskType`, `taskName` |

### HTTP Task Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.http.requests` | Counter | Number of HTTP requests | `method`, `host`, `status` |
| `lemline.http.request.duration` | Timer | HTTP request duration | `method`, `host` |
| `lemline.http.request.size` | Histogram | HTTP request size in bytes | `method`, `host` |
| `lemline.http.response.size` | Histogram | HTTP response size in bytes | `method`, `host` |
| `lemline.http.client.connections` | Gauge | Number of active HTTP client connections | `host` |
| `lemline.http.circuit.breaker.state` | Gauge | Circuit breaker state (0=closed, 1=open, 0.5=half-open) | `host` |
| `lemline.http.circuit.breaker.failures` | Counter | Number of failures counted by circuit breaker | `host` |

### Event Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.events.emitted` | Counter | Number of events emitted | `eventType` |
| `lemline.events.received` | Counter | Number of events received | `eventType` |
| `lemline.events.processing.duration` | Timer | Event processing duration | `eventType` |
| `lemline.events.correlation.active` | Gauge | Number of active event correlations | |
| `lemline.events.correlation.timeouts` | Counter | Number of event correlation timeouts | |

### Expression Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.expressions.evaluated` | Counter | Number of expressions evaluated | `expressionType` |
| `lemline.expressions.evaluation.duration` | Timer | Expression evaluation duration | `expressionType` |
| `lemline.expressions.evaluation.failures` | Counter | Number of expression evaluation failures | `expressionType`, `errorType` |

## System Metrics

### Database Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.database.connections.active` | Gauge | Number of active database connections | |
| `lemline.database.connections.idle` | Gauge | Number of idle database connections | |
| `lemline.database.connections.max` | Gauge | Maximum number of database connections | |
| `lemline.database.query.duration` | Timer | Database query duration | `queryType` |
| `lemline.database.transactions` | Counter | Number of database transactions | `status` |
| `lemline.database.errors` | Counter | Number of database errors | `errorType` |

### Messaging Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.messaging.messages.sent` | Counter | Number of messages sent | `destination` |
| `lemline.messaging.messages.received` | Counter | Number of messages received | `source` |
| `lemline.messaging.delivery.duration` | Timer | Message delivery duration | `destination` |
| `lemline.messaging.errors` | Counter | Number of messaging errors | `errorType` |
| `lemline.messaging.connections.active` | Gauge | Number of active messaging connections | |

### Outbox Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.outbox.records.created` | Counter | Number of outbox records created | `type` |
| `lemline.outbox.records.processed` | Counter | Number of outbox records processed | `type`, `status` |
| `lemline.outbox.processing.duration` | Timer | Outbox record processing duration | `type` |
| `lemline.outbox.records.pending` | Gauge | Number of pending outbox records | `type` |
| `lemline.outbox.processing.delay` | Histogram | Delay between record creation and processing | `type` |

### Resource Usage Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.system.cpu.usage` | Gauge | CPU usage percentage | |
| `lemline.system.memory.used` | Gauge | Memory used in bytes | |
| `lemline.system.memory.max` | Gauge | Maximum available memory in bytes | |
| `lemline.system.disk.free` | Gauge | Free disk space in bytes | `path` |
| `lemline.system.disk.total` | Gauge | Total disk space in bytes | `path` |
| `lemline.system.threads.count` | Gauge | Number of threads | |
| `lemline.system.open.files` | Gauge | Number of open files | |

### JVM Metrics

When running on the JVM, these additional metrics are available:

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `jvm.memory.used` | Gauge | JVM memory used | `area` |
| `jvm.memory.committed` | Gauge | JVM memory committed | `area` |
| `jvm.memory.max` | Gauge | JVM maximum memory | `area` |
| `jvm.gc.pause` | Timer | Garbage collection pause duration | `action`, `cause` |
| `jvm.gc.memory.promoted` | Counter | Count of promotions to old generation | |
| `jvm.gc.memory.allocated` | Counter | Count of allocations | |
| `jvm.threads.states` | Gauge | Thread states | `state` |
| `jvm.classes.loaded` | Gauge | Number of loaded classes | |

## Execution Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.execution.tasks.submitted` | Counter | Number of tasks submitted for execution | |
| `lemline.execution.tasks.completed` | Counter | Number of tasks completed | `status` |
| `lemline.execution.queue.size` | Gauge | Execution queue size | |
| `lemline.execution.active.threads` | Gauge | Number of active threads | |
| `lemline.execution.pool.size` | Gauge | Thread pool size | |
| `lemline.execution.rejected.tasks` | Counter | Number of rejected tasks | |

## Resilience Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.resilience.retries` | Counter | Number of retry operations | `operation` |
| `lemline.resilience.retry.attempts` | Histogram | Number of retry attempts per operation | `operation` |
| `lemline.resilience.circuit.breaker.state` | Gauge | Circuit breaker state (0=closed, 1=open, 0.5=half-open) | `id` |
| `lemline.resilience.circuit.breaker.failures` | Counter | Number of failures counted by circuit breaker | `id` |
| `lemline.resilience.circuit.breaker.successful.calls` | Counter | Number of successful calls through circuit breaker | `id` |
| `lemline.resilience.circuit.breaker.rejected.calls` | Counter | Number of rejected calls due to open circuit | `id` |
| `lemline.resilience.timeout.count` | Counter | Number of timeout operations | `operation` |

## Security Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.security.auth.attempts` | Counter | Number of authentication attempts | `type`, `status` |
| `lemline.security.auth.failures` | Counter | Number of authentication failures | `type`, `reason` |
| `lemline.security.secret.refreshes` | Counter | Number of secret refreshes | `status` |
| `lemline.security.secret.cache.size` | Gauge | Size of secrets cache | |
| `lemline.security.secret.access` | Counter | Number of secret accesses | `secretName` |

## Swagger/API Metrics

| Metric Name | Type | Description | Tags |
|-------------|------|-------------|------|
| `lemline.api.requests` | Counter | Number of API requests | `path`, `method`, `status` |
| `lemline.api.request.duration` | Timer | API request duration | `path`, `method` |
| `lemline.api.errors` | Counter | Number of API errors | `path`, `method`, `errorType` |

## Customizing Metrics

### Adding Tags

Additional tags can be added to metrics through configuration:

```properties
# Add environment tag to all metrics
lemline.metrics.tags.environment=production

# Add region tag to all metrics
lemline.metrics.tags.region=us-west
```

### Filtering Metrics

You can include or exclude specific metrics:

```properties
# Include only workflow and http metrics
lemline.metrics.include-patterns=lemline.workflow.*,lemline.http.*

# Exclude JVM metrics
lemline.metrics.exclude-patterns=jvm.*
```

### Customizing Metric Collection

```properties
# Adjust histogram buckets for HTTP request duration
lemline.metrics.distribution.percentiles.http.request.duration=0.5,0.95,0.99
lemline.metrics.distribution.slo.http.request.duration=10,50,100,500

# Set minimum expected value for specific gauge
lemline.metrics.minimum-expected-value.lemline.workflow.instances.active=1

# Set maximum expected value for specific gauge
lemline.metrics.maximum-expected-value.lemline.system.memory.used=1073741824
```

## Metric Endpoints

### Prometheus Endpoint

Metrics are exposed in Prometheus format at:

```
/metrics
```

Custom endpoint path can be configured:

```properties
quarkus.micrometer.export.prometheus.path=/prometheus
```

### Healthcheck Endpoints

Health metrics are exposed at:

```
/health
/health/live
/health/ready
```

### Metrics API

Programmatic access to metrics:

```java
@Inject
MeterRegistry registry;

public void recordCustomMetric(String workflowId) {
    registry.counter("lemline.custom.counter", "workflowId", workflowId).increment();
}
```

## Integrating with Monitoring Systems

### Prometheus and Grafana

1. Configure Prometheus to scrape metrics:

```yaml
scrape_configs:
  - job_name: 'lemline'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['lemline-host:8080']
```

2. Import the Lemline Grafana dashboard (ID: 15234) or create custom dashboards.

### Datadog Integration

Enable Datadog export:

```properties
quarkus.micrometer.export.datadog.enabled=true
quarkus.micrometer.export.datadog.apiKey=${DATADOG_API_KEY}
quarkus.micrometer.export.datadog.step=PT1M
```

### CloudWatch Integration

Enable CloudWatch export:

```properties
quarkus.micrometer.export.cloudwatch.enabled=true
quarkus.micrometer.export.cloudwatch.namespace=Lemline
quarkus.micrometer.export.cloudwatch.step=PT1M
```

## Recommended Alerts

Here are some recommended alerting thresholds:

| Metric | Threshold | Description |
|--------|-----------|-------------|
| `lemline.workflow.instances.failed` | Rate > 0.05 (5%) | High workflow failure rate |
| `lemline.workflow.instances.duration` | p95 > 300s | Slow workflow execution |
| `lemline.http.request.duration` | p95 > 2s | Slow HTTP requests |
| `lemline.database.connections.active` | > 90% of max | Database connection pool near capacity |
| `lemline.system.memory.used` | > 85% of max | High memory usage |
| `lemline.system.cpu.usage` | > 80% | High CPU usage |
| `lemline.outbox.records.pending` | > 1000 | Large outbox backlog |
| `lemline.execution.queue.size` | > 100 | Large execution queue |
| `lemline.http.circuit.breaker.state` | == 1 | Circuit breaker open |

## Related Resources

- [Monitoring Workflows](lemline-howto-monitor.md)
- [Observability Configuration](lemline-howto-observability.md)
- [Runner Configuration Reference](lemline-ref-config.md)
- [Environment Variables Reference](lemline-ref-env-vars.md)