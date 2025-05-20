---
title: How to monitor workflow execution
---

# How to monitor workflow execution

This guide explains how to monitor the execution of Lemline workflows, track their progress, view execution details, and set up comprehensive observability for your workflows.

## Understanding Workflow States

Before diving into monitoring, it's important to understand the possible states of a workflow instance:

| State | Description |
|-------|-------------|
| `PENDING` | The workflow instance is created but execution hasn't started |
| `RUNNING` | The workflow is actively executing |
| `WAITING` | The workflow is paused, waiting for an event or timer |
| `COMPLETED` | The workflow has successfully finished execution |
| `FAULTED` | The workflow encountered an unhandled error |
| `SUSPENDED` | The workflow was manually suspended |
| `CANCELLED` | The workflow was manually cancelled |

## Basic Monitoring Techniques

### Using the CLI

The Lemline CLI provides several commands for monitoring workflows:

#### Check Instance Status

```bash
lemline instance get <instance-id>
```

This returns details about the specified instance, including:
- Current state
- Start and end times (if applicable)
- Current position in the workflow
- Error information (if faulted)

#### List All Instances

To see all workflow instances:

```bash
lemline instance list
```

You can filter the list by workflow ID:

```bash
lemline instance list --workflow-id my-workflow
```

Or by status:

```bash
lemline instance list --status RUNNING
```

#### View Instance History

To see the execution history of an instance:

```bash
lemline instance history <instance-id>
```

This shows all state transitions the workflow has gone through, including:
- Task transitions
- Wait events
- Error occurrences
- Manual interventions

### Using the REST API

Lemline provides a REST API for programmatic monitoring:

#### Get Instance Status

```bash
curl -X GET http://localhost:8080/api/v1/instances/<instance-id>
```

Response:
```json
{
  "instanceId": "8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a",
  "workflowId": "my-workflow",
  "version": "1.0",
  "status": "RUNNING",
  "startedAt": "2023-09-10T14:30:00Z",
  "currentPosition": "/do/0/callHttp",
  "variables": {
    "orderStatus": "processing",
    "customerId": "CUST-123"
  }
}
```

#### List Instances

```bash
curl -X GET http://localhost:8080/api/v1/instances?workflowId=my-workflow&status=RUNNING
```

#### Get Instance History

```bash
curl -X GET http://localhost:8080/api/v1/instances/<instance-id>/history
```

## Advanced Monitoring

### Listening to Lifecycle Events

Lemline emits events for significant workflow lifecycle events. You can listen to these events to build real-time monitoring dashboards or trigger additional processes.

To start listening for workflow events:

```bash
lemline listen events
```

This command connects to the configured message broker and displays workflow lifecycle events as they occur:

```
Listening for events...
🐘 Connected to message broker

Event received:
  Type: WORKFLOW_STARTED
  Workflow: order-processing (1.0)
  Instance: 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a
  Timestamp: 2023-09-10T14:30:00Z

Event received:
  Type: TASK_COMPLETED
  Workflow: order-processing (1.0)
  Instance: 8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a
  Task: ValidateOrder
  Position: /do/0/validateOrder
  Timestamp: 2023-09-10T14:30:02Z
```

You can filter events by type:

```bash
lemline listen events --type WORKFLOW_COMPLETED
```

### Collecting and Analyzing Metrics

Lemline exposes metrics via Micrometer that can be scraped by Prometheus or similar monitoring systems:

```bash
curl http://localhost:8080/q/metrics
```

Key workflow metrics include:

- `lemline_workflows_active`: Number of currently active workflow instances
- `lemline_workflows_completed`: Count of completed workflow instances
- `lemline_workflows_faulted`: Count of faulted workflow instances
- `lemline_task_execution_time`: Histogram of task execution times
- `lemline_workflow_execution_time`: Histogram of end-to-end workflow execution times
- `lemline_database_operations`: Count of database operations performed
- `lemline_message_operations`: Count of message operations performed

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

To enable detailed logging for workflow execution:

```yaml
# In application.properties
quarkus.log.category."com.lemline".level=DEBUG
```

This provides detailed logs of:
- Task transitions
- Expression evaluations
- Input/output transformations
- Error details

### Inspecting Node States

To understand why a workflow behaved in a certain way, inspect the node states:

```bash
lemline instance states <instance-id>
```

This shows the state of each node in the workflow, including:
- Input and output data
- Variables
- Transformation results
- Error details

### Tracing Workflow Execution

For complex workflows, enable distributed tracing:

```yaml
# In application.properties
quarkus.opentelemetry.enabled=true
quarkus.opentelemetry.tracer.exporter.otlp.endpoint=http://jaeger:4317
```

This integrates with systems like Jaeger or Zipkin to provide end-to-end tracing of workflow execution, including:
- Task durations
- External service calls
- Database operations
- Message broker interactions

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

4. **Audit Trail**: Maintain an audit trail of workflow actions:
   - All manual interventions (cancellations, suspensions)
   - All automatic recovery actions
   - Changes to workflow definitions

5. **Performance Monitoring**: Track and optimize:
   - Database operation counts and durations
   - Message broker interaction patterns
   - External service call performance
   - Resource utilization (CPU, memory)

## Example: Setting Up a Basic Monitoring Dashboard

This example uses Prometheus and Grafana:

1. Configure Prometheus to scrape Lemline metrics:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'lemline'
    metrics_path: '/q/metrics'
    static_configs:
      - targets: ['lemline:8080']
```

2. Create a Grafana dashboard with these panels:
   - Workflow instance counts by status
   - Average workflow execution time
   - Error rates
   - Database and message broker operations
   - System resource utilization

3. Set up alerts for critical conditions:
   - Workflows in FAULTED state
   - Workflows stuck in RUNNING for > 1 hour
   - Error rate exceeding threshold
   - Database connection issues

## Next Steps

- Learn how to [manage workflow lifecycle](lemline-howto-lifecycle.md)
- Explore [how to debug faults in task execution](lemline-howto-debug.md)
- Understand [how to configure observability](lemline-howto-observability.md) in depth