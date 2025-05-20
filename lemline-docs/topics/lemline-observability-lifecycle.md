# Workflow Lifecycle Observability

This document explains how to monitor and observe the complete lifecycle of workflows in Lemline, from creation to completion. Understanding and tracking workflow lifecycles is essential for troubleshooting, performance optimization, and ensuring system reliability.

## Workflow Lifecycle Phases

A workflow in Lemline goes through several distinct phases during its lifecycle:

1. **Definition** - Workflow is defined and registered in the system
2. **Instantiation** - Workflow instance is created from a definition
3. **Execution** - Workflow instance executes through its defined steps
4. **Suspension** - Workflow may be suspended while waiting for events or timeouts
5. **Resumption** - Workflow resumes execution after suspension
6. **Completion** - Workflow reaches a terminal state (completed, failed, or terminated)
7. **Archival** - Workflow data is archived for historical record

## Core Observability Events

Lemline emits structured events for each major lifecycle transition:

| Event Type | Description | Key Data Points |
|------------|-------------|----------------|
| `workflow.definition.created` | Workflow definition registered | Namespace, name, version |
| `workflow.definition.updated` | Workflow definition updated | Namespace, name, version, changes |
| `workflow.definition.deleted` | Workflow definition deleted | Namespace, name, version |
| `workflow.instance.created` | Workflow instance created | Instance ID, definition reference, input data |
| `workflow.instance.started` | Workflow execution started | Instance ID, start time |
| `workflow.node.executing` | Workflow node execution started | Instance ID, node position, node type |
| `workflow.node.completed` | Workflow node execution completed | Instance ID, node position, duration, output |
| `workflow.node.error` | Workflow node execution failed | Instance ID, node position, error details |
| `workflow.instance.suspended` | Workflow execution suspended | Instance ID, reason, resume trigger |
| `workflow.instance.resumed` | Workflow execution resumed | Instance ID, suspension duration, trigger |
| `workflow.instance.completed` | Workflow execution completed | Instance ID, total duration, output |
| `workflow.instance.failed` | Workflow execution failed | Instance ID, error details, duration |
| `workflow.instance.terminated` | Workflow execution manually terminated | Instance ID, reason, user |

## Monitoring Workflow Definitions

### Tracking Definition Changes

Monitoring workflow definition lifecycle is important for change management and auditing:

```bash
# List all workflow definitions
lemline definition list

# Get specific workflow definition details
lemline definition get -n example.workflow -v 1.0.0

# View definition history
lemline definition history -n example.workflow
```

### Monitoring Definition Usage

Track how workflow definitions are used across your system:

```bash
# Count active instances per definition
lemline definition stats -n example.workflow -v 1.0.0

# View definition dependency graph
lemline definition dependencies -n example.workflow -v 1.0.0
```

## Monitoring Workflow Instances

### Active Instance Monitoring

Monitor currently executing workflow instances:

```bash
# List all active workflow instances
lemline instance list --status active

# Count instances by definition
lemline instance count --group-by definition

# View instance details
lemline instance get <instance-id>
```

### Real-time Execution Monitoring

For detailed real-time monitoring, Lemline provides a streaming API:

```bash
# Stream events for a specific instance
lemline instance events <instance-id> --follow

# Stream all workflow events
lemline events --type workflow.* --follow
```

### Instance State Visualization

Lemline provides visualization tools for workflow instance state:

```bash
# Generate a state diagram for an instance
lemline instance visualize <instance-id> --format svg --output workflow.svg

# Generate an execution timeline
lemline instance timeline <instance-id> --format html --output timeline.html
```

## Key Metrics for Workflow Lifecycle

### Workflow Definition Metrics

| Metric | Description | Dimension Keys |
|--------|-------------|----------------|
| `lemline_definition_count` | Total count of workflow definitions | namespace, version |
| `lemline_definition_instance_count` | Number of instances created from definition | namespace, name, version |
| `lemline_definition_update_count` | Number of times a definition was updated | namespace, name |

### Workflow Instance Metrics

| Metric | Description | Dimension Keys |
|--------|-------------|----------------|
| `lemline_instance_duration_seconds` | Total workflow execution time | namespace, name, version, status |
| `lemline_instance_node_count` | Number of nodes executed in workflow | namespace, name, version |
| `lemline_instance_error_count` | Number of errors encountered | namespace, name, version, error_type |
| `lemline_instance_status_count` | Count of instances by status | namespace, name, version, status |
| `lemline_instance_suspension_duration_seconds` | Time spent in suspended state | namespace, name, version |

### Node Execution Metrics

| Metric | Description | Dimension Keys |
|--------|-------------|----------------|
| `lemline_node_execution_duration_seconds` | Time taken to execute a node | namespace, name, version, node_type, node_position |
| `lemline_node_execution_count` | Count of node executions | namespace, name, version, node_type, node_position |
| `lemline_node_error_count` | Count of node execution errors | namespace, name, version, node_type, node_position, error_type |

## Setting Up Comprehensive Lifecycle Monitoring

### Configuration for Lifecycle Events

Configure Lemline to emit lifecycle events to your observability platform:

```yaml
lemline:
  observability:
    events:
      enabled: true
      destinations:
        - type: webhook
          url: https://monitoring.example.com/events
        - type: kafka
          bootstrap-servers: kafka.example.com:9092
          topic: lemline-events
    metrics:
      enabled: true
      reporters:
        - type: prometheus
          port: 9090
```

### Leveraging External Monitoring Systems

Integrate Lemline with external monitoring platforms:

1. **Prometheus/Grafana** - For metrics collection and visualization
2. **OpenTelemetry** - For distributed tracing
3. **ELK Stack** - For log aggregation and search
4. **Event Streaming Platforms** - For real-time event processing

## Building Lifecycle Dashboards

An effective lifecycle monitoring dashboard should include:

1. **Instance Status Overview** - Count of workflows by status
2. **Execution Timeline** - Visual representation of workflow execution steps
3. **Error Rate Trends** - Error rates over time by workflow type
4. **Duration Analysis** - Execution time distribution by workflow type
5. **Bottleneck Identification** - Nodes with longest execution times
6. **Suspension Analysis** - Frequency and duration of workflow suspensions

Example Grafana dashboard panels:

```
# Node execution time histogram
sum by (node_type) (rate(lemline_node_execution_duration_seconds_sum[5m])) 
/ 
sum by (node_type) (rate(lemline_node_execution_duration_seconds_count[5m]))

# Workflow success/failure ratio
sum(increase(lemline_instance_status_count{status="completed"}[1h])) 
/ 
sum(increase(lemline_instance_status_count{status=~"completed|failed"}[1h]))

# Average workflow duration trend
avg by (namespace, name) (lemline_instance_duration_seconds{status="completed"})
```

## Lifecycle Alerts and Notifications

Set up alerts for critical lifecycle events:

1. **Elevated Error Rates** - Alert when error rates exceed thresholds
2. **Workflow Execution Time** - Alert on unusually long execution times
3. **Stuck Workflows** - Alert on workflows suspended longer than expected
4. **Definition Changes** - Notify on workflow definition updates
5. **Failed Workflows** - Alert on business-critical workflow failures

Example Prometheus alert rules:

```yaml
groups:
- name: workflow-alerts
  rules:
  - alert: WorkflowErrorRateHigh
    expr: sum(increase(lemline_instance_status_count{status="failed"}[15m])) / sum(increase(lemline_instance_status_count[15m])) > 0.05
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High workflow error rate"
      description: "Workflow error rate is above 5% for the last 15 minutes"

  - alert: WorkflowExecutionSlow
    expr: lemline_instance_duration_seconds{quantile="0.95"} > 600
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: "Slow workflow execution"
      description: "95th percentile of workflow execution time is above 10 minutes"

  - alert: WorkflowsStuck
    expr: sum(lemline_instance_status_count{status="suspended"}) > 0 and max(lemline_instance_suspension_duration_seconds) > 3600
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Workflows stuck in suspended state"
      description: "Workflows have been suspended for more than 1 hour"
```

## Workflow Lifecycle Audit Trail

Maintain a comprehensive audit trail of workflow lifecycle events for compliance and debugging:

```yaml
lemline:
  audit:
    enabled: true
    retention:
      days: 90
    storage:
      type: database
      table: workflow_audit_events
    events:
      - type: workflow.definition.*
      - type: workflow.instance.*
```

Query the audit trail for compliance reporting or troubleshooting:

```sql
-- Find all changes to a particular workflow definition
SELECT * FROM workflow_audit_events 
WHERE event_type LIKE 'workflow.definition.%' 
AND data->>'namespace' = 'example' 
AND data->>'name' = 'order-processing';

-- Track complete lifecycle of a workflow instance
SELECT event_type, timestamp, data 
FROM workflow_audit_events
WHERE data->>'instanceId' = '12345678-90ab-cdef-1234-567890abcdef'
ORDER BY timestamp;
```

## Advanced Lifecycle Analysis

### Workflow Duration Breakdown

Analyze where time is spent in workflow execution:

```sql
-- Get execution time breakdown by node type
SELECT 
  data->>'node_type' as node_type,
  AVG(CAST(data->>'duration_ms' AS NUMERIC)) as avg_duration_ms,
  COUNT(*) as execution_count
FROM workflow_audit_events
WHERE event_type = 'workflow.node.completed'
AND data->>'workflow_name' = 'order-processing'
GROUP BY data->>'node_type'
ORDER BY avg_duration_ms DESC;
```

### Workflow Failure Analysis

Identify common failure patterns:

```sql
-- Get top error types by frequency
SELECT 
  data->>'error_type' as error_type,
  COUNT(*) as error_count
FROM workflow_audit_events
WHERE event_type = 'workflow.node.error'
GROUP BY data->>'error_type'
ORDER BY error_count DESC;

-- Find workflows that fail at the same node
SELECT 
  data->>'node_position' as failing_node,
  COUNT(*) as failure_count
FROM workflow_audit_events
WHERE event_type = 'workflow.node.error'
AND data->>'workflow_name' = 'order-processing'
GROUP BY data->>'node_position'
ORDER BY failure_count DESC;
```

## Best Practices for Workflow Lifecycle Observability

1. **Complete Event Coverage** - Configure monitoring for all lifecycle phases
2. **Correlation IDs** - Use correlation IDs to track related workflows
3. **Structured Logging** - Use consistent structured formats for all logs
4. **Sampling Strategies** - For high-volume systems, implement intelligent sampling
5. **Retention Policies** - Define clear retention periods for different types of data
6. **Aggregation** - Aggregate metrics at appropriate levels for efficient querying
7. **Contextual Information** - Include business context in monitoring data
8. **Alerting Thresholds** - Regularly review and tune alerting thresholds
9. **Historical Comparisons** - Compare current behavior against historical baselines
10. **Actionable Insights** - Ensure monitoring leads to clear, actionable insights

## Conclusion

Comprehensive lifecycle observability is essential for operating Lemline workflows at scale. By tracking workflows through each phase of their lifecycle, you can identify issues early, optimize performance, maintain compliance, and ensure reliable operation.

The combination of lifecycle events, metrics, visualizations, and alerts provides a complete view of workflow behavior, enabling both immediate troubleshooting and long-term improvement of your workflow-based applications.

For more details on specific types of observability data, see:
- [Performance Observability](lemline-observability-performance.md)
- [I/O Observability](lemline-observability-io.md)
- [Sizing and Scaling](lemline-observability-sizing.md)