# Sizing and Scaling Observability

This document provides guidance on monitoring, analyzing, and optimizing the sizing and scaling aspects of Lemline deployments. Proper sizing and scaling are essential for ensuring system reliability, cost efficiency, and performance as workflow volume and complexity grow.

## Understanding Lemline Resource Requirements

Lemline's resource requirements are determined by several key factors:

### Workflow Execution Factors

| Factor | Description | Impact |
|--------|-------------|--------|
| Workflow Concurrency | Number of workflows executing simultaneously | Memory, CPU, connection pools |
| Workflow Complexity | Number and type of nodes in workflows | Memory, CPU |
| Workflow Duration | Average execution time of workflows | Resource occupation duration |
| I/O Intensity | Amount of I/O operations performed | Network, connection pools |
| Data Volume | Size of data processed by workflows | Memory, network bandwidth |
| Event Processing | Volume of events being processed | Memory, message broker connections |

### System Resource Components

| Component | Description | Sizing Considerations |
|-----------|-------------|----------------------|
| Worker Threads | Threads executing workflow tasks | CPU cores, memory per thread |
| Connection Pools | Database and HTTP connection pools | External system capacity, network limits |
| State Storage | Storage for workflow state | Database size, I/O capacity |
| Event Queues | Queues for event processing | Message broker capacity, consumer count |
| Caches | Memory caches for workflow data | Memory allocation, eviction policies |

## Key Sizing and Scaling Metrics

Monitor these metrics to inform sizing and scaling decisions:

### Resource Utilization Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_cpu_utilization` | CPU usage percentage | instance, node |
| `lemline_memory_usage_bytes` | Memory usage in bytes | instance, node |
| `lemline_disk_usage_bytes` | Disk usage in bytes | instance, node |
| `lemline_network_io_bytes` | Network I/O bytes | instance, direction |
| `lemline_thread_pool_utilization` | Worker thread pool utilization | instance, pool_name |

### Capacity Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_workflow_concurrency` | Number of concurrent workflow executions | instance, namespace, name |
| `lemline_workflow_queue_depth` | Depth of workflow execution queue | instance, namespace |
| `lemline_connection_pool_utilization` | Connection pool utilization percentage | instance, pool_type |
| `lemline_database_size_bytes` | Database size in bytes | instance, database |
| `lemline_event_queue_size` | Event queue size | instance, queue_name |

### Throughput Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_workflow_throughput` | Workflows completed per minute | instance, namespace, name |
| `lemline_node_execution_throughput` | Nodes executed per minute | instance, node_type |
| `lemline_io_operations_per_second` | I/O operations per second | instance, operation_type |
| `lemline_database_operations_per_second` | Database operations per second | instance, operation_type |
| `lemline_event_processing_rate` | Events processed per second | instance, event_type |

### Scaling Event Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_scaling_events` | Count of scaling events | instance, direction |
| `lemline_scaling_duration_seconds` | Time taken for scaling operations | instance, direction |
| `lemline_autoscaling_threshold_breaches` | Count of autoscaling threshold breaches | instance, metric |

## Setting Up Sizing and Scaling Observability

### Basic Configuration

Enable sizing and scaling metrics in your Lemline configuration:

```yaml
lemline:
  observability:
    sizing:
      enabled: true
      collection-interval: 15s
      resource-metrics: true
      capacity-metrics: true
      throughput-metrics: true
    scaling:
      events:
        enabled: true
      threshold-monitoring:
        enabled: true
```

### Integration with Cloud Provider Metrics

For cloud deployments, integrate with cloud provider metrics:

```yaml
lemline:
  observability:
    cloud:
      aws:
        enabled: true
        cloudwatch:
          enabled: true
          namespace: Lemline
          dimensions:
            - Environment
            - ServiceName
      gcp:
        enabled: true
        stackdriver:
          enabled: true
          project-id: lemline-project
```

### Kubernetes Integration

For Kubernetes deployments, enable Kubernetes metrics:

```yaml
lemline:
  observability:
    kubernetes:
      enabled: true
      pod-metrics: true
      horizontal-pod-autoscaler-metrics: true
      custom-metrics-api:
        enabled: true
        metrics:
          - name: lemline_workflow_concurrency
          - name: lemline_workflow_queue_depth
```

## Visualizing Sizing and Scaling Metrics

### Capacity Dashboard

Create a Grafana dashboard for capacity monitoring:

1. **Current vs. Maximum Capacity**:
   ```
   sum(lemline_workflow_concurrency) / sum(lemline_max_workflow_concurrency) * 100
   ```

2. **Resource Utilization Heatmap**:
   ```
   sum(lemline_cpu_utilization) by (instance)
   ```

3. **Connection Pool Usage**:
   ```
   sum(lemline_connection_pool_utilization) by (pool_type, instance)
   ```

4. **Queue Depth Trends**:
   ```
   sum(lemline_workflow_queue_depth) by (namespace)
   ```

### Scaling Events Timeline

Create a timeline of scaling events:

```
lemline_scaling_events{direction="up"} or lemline_scaling_events{direction="down"}
```

### Resource Prediction Panel

For resource prediction:

```
predict_linear(lemline_memory_usage_bytes[1h], 3600 * 24)
```

## Capacity Planning

### Workflow Profiling for Capacity Planning

Profile workflows to understand their resource requirements:

```bash
lemline profile resource-usage -n example.workflow -v 1.0.0 --duration 30m
```

The profile output provides:
- Peak memory usage per workflow
- Average CPU utilization per workflow
- Average database connections used
- Average HTTP connections used
- I/O operations per workflow execution

### Load Testing for Scaling

Conduct load tests to verify scaling capabilities:

```bash
lemline benchmark load-test --workflows-file workflows.yml --ramp-up 5m --plateau 15m --ramp-down 5m --concurrent-max 100
```

The load test report includes:
- Maximum sustainable throughput
- Resource utilization at different load levels
- Scaling event timelines
- Performance degradation points

## Scaling Strategies

### Vertical Scaling Configuration

Configure resource allocation for vertical scaling:

```yaml
lemline:
  resources:
    memory:
      min: 1G
      max: 4G
    cpu:
      min: 2
      max: 8
    jvm:
      heap:
        min: 512M
        max: 3G
```

### Horizontal Scaling Configuration

Configure horizontal scaling parameters:

```yaml
lemline:
  scaling:
    horizontal:
      min-instances: 2
      max-instances: 10
      scale-up:
        threshold: 70  # CPU utilization percentage
        cooldown-period: 3m
      scale-down:
        threshold: 30  # CPU utilization percentage
        cooldown-period: 5m
```

### Auto-Scaling Based on Queue Depth

Set up auto-scaling based on workflow queue depth:

```yaml
lemline:
  scaling:
    metrics:
      - type: workflow-queue-depth
        threshold: 50
        comparison: ">="
        period: 2m
        action: scale-up
        instances: 1
        cooldown-period: 3m
```

## Sizing Analysis Case Studies

### Case Study 1: Memory Sizing for Complex Workflows

**Problem:** Memory usage spikes during complex workflow execution

**Analysis Approach:**
1. Monitor memory usage patterns across workflow types
2. Correlate memory usage with workflow characteristics (size of data, node types)
3. Analyze garbage collection metrics

**Solution:**
```yaml
lemline:
  resources:
    memory:
      workflows:
        default:
          overhead: 256M
          per-instance: 10M
        "data-processing":
          overhead: 512M
          per-instance: 20M
    jvm:
      heap:
        min: 1G
        max: 4G
      gc:
        algorithm: G1
        settings:
          MaxGCPauseMillis: 200
```

### Case Study 2: Database Connection Pool Sizing

**Problem:** Database connection contention during peak loads

**Analysis Approach:**
1. Monitor connection pool utilization patterns
2. Analyze wait times for database connections
3. Correlate connection usage with workflow volume

**Solution:**
```yaml
lemline:
  database:
    connection-pool:
      min-connections: 10
      max-connections: 50
      max-idle-time: 10m
      sizing-strategy:
        formula: "10 + (concurrent_workflows * 0.3)"
        min: 10
        max: 50
```

### Case Study 3: Horizontal Scaling for Bursty Workloads

**Problem:** Slow response to sudden increases in workflow volume

**Analysis Approach:**
1. Analyze pattern of workflow submission bursts
2. Monitor queue depth during burst periods
3. Measure scale-up response time

**Solution:**
```yaml
lemline:
  scaling:
    horizontal:
      predictive:
        enabled: true
        window: 1h
        prediction-horizon: 15m
      reactive:
        workflow-queue:
          threshold: 30
          scale-up-increment: 2
          cooldown-period: 1m
```

## Auto-scaling Metrics and Thresholds

Select appropriate metrics and thresholds for auto-scaling:

### CPU-Based Auto-scaling

```yaml
lemline:
  scaling:
    metrics:
      - type: cpu-utilization
        threshold: 70  # Percentage
        comparison: ">="
        period: 3m
        action: scale-up
        instances: 1
```

### Memory-Based Auto-scaling

```yaml
lemline:
  scaling:
    metrics:
      - type: memory-utilization
        threshold: 80  # Percentage
        comparison: ">="
        period: 2m
        action: scale-up
        instances: 1
```

### Queue-Based Auto-scaling

```yaml
lemline:
  scaling:
    metrics:
      - type: workflow-queue-depth
        threshold: 100  # Number of queued workflows
        comparison: ">="
        period: 1m
        action: scale-up
        instances: 2
```

### Scheduled Auto-scaling

```yaml
lemline:
  scaling:
    schedules:
      - cron: "0 8 * * MON-FRI"  # Weekdays at 8 AM
        instances: 5
        description: "Business hours scaling"
      - cron: "0 18 * * MON-FRI"  # Weekdays at 6 PM
        instances: 2
        description: "After business hours scaling"
```

## Resource Estimation Models

### Memory Requirement Estimation

Estimate memory requirements based on workflow characteristics:

```
Total Memory = Base Memory + (Workflow Concurrency × Memory Per Workflow)
```

Where:
- Base Memory: ~512 MB (JVM, system overhead)
- Memory Per Workflow: Depends on workflow complexity
  - Simple workflows: ~5-10 MB
  - Medium complexity: ~10-50 MB
  - Complex data processing: ~50-200 MB

### CPU Requirement Estimation

Estimate CPU requirements based on workflow throughput:

```
Total vCPUs = Base vCPUs + (Peak Workflows Per Second × vCPU Per Workflow)
```

Where:
- Base vCPUs: 1-2 (system operations)
- vCPU Per Workflow: Depends on computation intensity
  - I/O bound workflows: ~0.1-0.2 vCPU
  - Balanced workflows: ~0.2-0.5 vCPU
  - CPU intensive workflows: ~0.5-2.0 vCPU

### Connection Pool Sizing

Size connection pools based on concurrent workflows:

```
Pool Size = Base Connections + (Concurrent Workflows × Connections Per Workflow)
```

Where:
- Base Connections: 5-10 (system operations)
- Connections Per Workflow: Depends on I/O patterns
  - Low I/O: ~0.1 connections
  - Medium I/O: ~0.3 connections
  - High I/O: ~0.5-1.0 connections

## Sizing and Scaling Alerts

Set up alerts for sizing and scaling issues:

```yaml
alerts:
  - name: HighResourceUtilization
    expr: avg(lemline_cpu_utilization) > 85 or avg(lemline_memory_usage_bytes / lemline_memory_max_bytes * 100) > 85
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High resource utilization detected"
      description: "CPU or memory utilization is above 85% for the last 5 minutes"

  - name: WorkflowQueueBacklog
    expr: sum(lemline_workflow_queue_depth) > 200
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Workflow queue backlog detected"
      description: "More than 200 workflows are waiting in the queue"

  - name: ConnectionPoolSaturation
    expr: max(lemline_connection_pool_utilization) > 90
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Connection pool near saturation"
      description: "Connection pool utilization is above 90%"

  - name: FrequentScalingEvents
    expr: sum(increase(lemline_scaling_events[1h])) > 10
    labels:
      severity: warning
    annotations:
      summary: "Frequent scaling events detected"
      description: "More than 10 scaling events in the last hour"
```

## Deployment Model-Specific Sizing

### On-Premises Sizing

For on-premises deployments, consider:

```yaml
lemline:
  deployment:
    on-premises:
      node-allocation:
        workflow-engine:
          cpu: 8
          memory: 16G
        database:
          cpu: 4
          memory: 8G
        message-broker:
          cpu: 4
          memory: 8G
      network:
        internal-bandwidth: 10Gbps
        external-bandwidth: 1Gbps
```

### Kubernetes Sizing

For Kubernetes deployments:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lemline-workflow-engine
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: lemline
        resources:
          requests:
            memory: "2Gi"
            cpu: "2"
          limits:
            memory: "4Gi"
            cpu: "4"
        env:
        - name: JAVA_OPTS
          value: "-Xms1G -Xmx3G -XX:+UseG1GC"
---
apiVersion: autoscaling/v2beta2
kind: HorizontalPodAutoscaler
metadata:
  name: lemline-hpa
spec:
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Pods
    pods:
      metric:
        name: lemline_workflow_queue_depth
      target:
        type: AverageValue
        averageValue: 50
```

### Serverless Sizing

For serverless deployments:

```yaml
lemline:
  serverless:
    function:
      memory: 1024
      timeout: 5m
      concurrency:
        reserved: 50
        maximum: 200
    provisioned-concurrency:
      enabled: true
      minimum: 10
      maximum: 50
      target-utilization: 0.7
```

## Advanced Sizing and Scaling Techniques

### Predictive Scaling

Implement predictive scaling based on historical patterns:

```yaml
lemline:
  scaling:
    predictive:
      enabled: true
      algorithm: linear-regression
      training-data-window: 7d
      forecast-window: 1h
      confidence-threshold: 0.7
      update-frequency: 1h
```

### Workflow-Aware Scaling

Configure different scaling policies for different workflow types:

```yaml
lemline:
  scaling:
    workflow-aware:
      enabled: true
      policies:
        "data-processing":
          resource-multiplier: 2.0
          scale-up-threshold: 60
        "user-facing":
          resource-multiplier: 1.0
          scale-up-threshold: 70
```

### Scaling Based on Business Metrics

Scale based on business metrics, not just technical ones:

```yaml
lemline:
  scaling:
    business-metrics:
      enabled: true
      metrics:
        - name: orders_per_minute
          source: prometheus
          query: sum(rate(order_created_total[5m]))
          threshold: 1000
          action: scale-up
          instances: 2
```

## Best Practices for Sizing and Scaling

1. **Establish Resource Profiles** - Create resource profiles for different workflow types
2. **Regular Load Testing** - Conduct regular load tests to validate scaling policies
3. **Overprovisioning Strategy** - Develop a clear overprovisioning strategy for unpredictable loads
4. **Right-sizing** - Regularly evaluate and adjust resource allocations
5. **Scaling Limits** - Set clear upper limits for auto-scaling to prevent runaway costs
6. **Graceful Degradation** - Implement graceful degradation when approaching resource limits
7. **Cost Monitoring** - Monitor resource costs alongside performance metrics
8. **Capacity Forecasting** - Implement capacity forecasting based on growth trends
9. **Multi-dimensional Scaling** - Consider multiple metrics when making scaling decisions
10. **Scaling Documentation** - Document scaling decisions and their outcomes

## Conclusion

Effective sizing and scaling observability is essential for maintaining reliable, efficient, and cost-effective workflow operations in Lemline. By systematically monitoring, analyzing, and optimizing resource allocation and scaling behavior, you can ensure your system meets performance requirements while maximizing resource efficiency.

The combination of resource metrics, capacity planning, load testing, and intelligent scaling policies provides a comprehensive approach to sizing your Lemline deployment appropriately for both current needs and future growth.

For more details on other observability aspects, see:
- [Lifecycle Observability](lemline-observability-lifecycle.md)
- [Performance Observability](lemline-observability-performance.md)
- [I/O Observability](lemline-observability-io.md)