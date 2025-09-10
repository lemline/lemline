# Performance Observability

This document explains how to monitor, analyze, and optimize the performance of Lemline workflows. Performance observability is critical for identifying bottlenecks, setting appropriate SLAs, capacity planning, and ensuring that workflows meet their performance objectives.

## Key Performance Metrics

Lemline provides comprehensive performance metrics across different layers of the system:

### Workflow-Level Metrics

| Metric | Description | Unit | Collection Method |
|--------|-------------|------|------------------|
| `workflow_execution_duration` | Total time from start to completion | milliseconds | End-to-end measurement |
| `workflow_execution_node_count` | Number of nodes executed | count | Aggregation of node executions |
| `workflow_active_instances` | Currently active workflow instances | count | System state query |
| `workflow_throughput` | Workflows completed per minute | count/minute | Rate calculation |
| `workflow_memory_usage` | Memory consumed per workflow instance | bytes | Runtime monitoring |
| `workflow_execution_cpu_time` | CPU time consumed per workflow | milliseconds | Runtime monitoring |

### Node-Level Metrics

| Metric | Description | Unit | Collection Method |
|--------|-------------|------|------------------|
| `node_execution_duration` | Time to execute a specific node | milliseconds | Node timing |
| `node_execution_count` | Number of times a node is executed | count | Execution counter |
| `node_memory_usage` | Memory consumed during node execution | bytes | Runtime monitoring |
| `node_cpu_time` | CPU time for node execution | milliseconds | Runtime monitoring |
| `node_io_bytes` | Bytes read/written during node execution | bytes | I/O tracking |
| `node_retry_count` | Number of retries per node | count | Retry counter |

### System-Level Metrics

| Metric | Description | Unit | Collection Method |
|--------|-------------|------|------------------|
| `system_workflow_queue_depth` | Pending workflows in execution queue | count | Queue monitoring |
| `system_workflow_start_delay` | Delay between request and execution start | milliseconds | Timing measurement |
| `system_worker_utilization` | Percentage of worker capacity used | percentage | Resource monitoring |
| `system_workflow_executor_count` | Number of active workflow executors | count | System state query |
| `system_memory_usage` | Total memory usage of Lemline runtime | bytes | Runtime monitoring |
| `system_cpu_usage` | Total CPU usage of Lemline runtime | percentage | Runtime monitoring |

## Setting Up Performance Monitoring

### Basic Monitoring Configuration

Enable performance monitoring in your Lemline configuration:

```yaml
lemline:
  observability:
    metrics:
      enabled: true
      performance:
        enabled: true
        collection-interval: 15s
        detailed-node-metrics: true
      exporters:
        - type: prometheus
          port: 9090
        - type: jmx
          enabled: true
        - type: opentelemetry
          endpoint: https://telemetry.example.com
```

### JVM-Specific Monitoring

For JVM-based deployments, add JVM metrics monitoring:

```yaml
lemline:
  observability:
    metrics:
      jvm:
        enabled: true
        memory: true
        threads: true
        garbage-collection: true
        classloading: true
```

### Integration with Prometheus

Example Prometheus scrape configuration:

```yaml
scrape_configs:
  - job_name: 'lemline'
    metrics_path: '/metrics'
    scrape_interval: 15s
    static_configs:
      - targets: ['lemline-server:9090']
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: lemline
        action: keep
```

## Performance Visualization

### Basic Grafana Dashboard

Create a Grafana dashboard with essential performance panels:

1. **Workflow Execution Duration**:
   ```
   histogram_quantile(0.95, sum(rate(lemline_workflow_execution_duration_bucket{namespace="$namespace",name="$workflow"}[5m])) by (le))
   ```

2. **Node Execution Heatmap**:
   ```
   sum(increase(lemline_node_execution_duration_sum{namespace="$namespace",name="$workflow"}[1m])) by (node_type)
   /
   sum(increase(lemline_node_execution_duration_count{namespace="$namespace",name="$workflow"}[1m])) by (node_type)
   ```

3. **System Resource Utilization**:
   ```
   sum(rate(process_cpu_seconds_total{job="lemline"}[1m])) * 100
   ```

4. **Workflow Throughput**:
   ```
   sum(rate(lemline_workflow_completed_total{namespace="$namespace"}[5m])) by (name)
   ```

### Advanced Performance Dashboard

For more detailed performance analysis, create an advanced dashboard with:

1. **Execution Time Breakdown by Node Type**:
   ```
   sum(rate(lemline_node_execution_duration_sum[5m])) by (node_type)
   /
   sum(rate(lemline_node_execution_duration_count[5m])) by (node_type)
   ```

2. **Request Latency Percentiles**:
   ```
   histogram_quantile(0.50, sum(rate(lemline_workflow_execution_duration_bucket{namespace="$namespace",name="$workflow"}[5m])) by (le))
   histogram_quantile(0.90, sum(rate(lemline_workflow_execution_duration_bucket{namespace="$namespace",name="$workflow"}[5m])) by (le))
   histogram_quantile(0.95, sum(rate(lemline_workflow_execution_duration_bucket{namespace="$namespace",name="$workflow"}[5m])) by (le))
   histogram_quantile(0.99, sum(rate(lemline_workflow_execution_duration_bucket{namespace="$namespace",name="$workflow"}[5m])) by (le))
   ```

3. **Worker Pool Saturation**:
   ```
   sum(lemline_workflow_active_instances) / sum(lemline_system_max_capacity)
   ```

4. **Memory Usage Trend**:
   ```
   sum(lemline_system_memory_bytes) by (instance)
   ```

## Performance Testing and Benchmarking

### Creating a Performance Baseline

Establish performance baselines for your workflows:

1. **Single-Workflow Baseline**:
   ```bash
   lemline benchmark run -n example.workflow -v 1.0.0 --iterations 100 --concurrent 1
   ```

2. **Concurrency Testing**:
   ```bash
   lemline benchmark run -n example.workflow -v 1.0.0 --iterations 100 --concurrent 10,20,50,100
   ```

3. **Load Profile Testing**:
   ```bash
   lemline benchmark run -n example.workflow -v 1.0.0 --profile ramp-up --duration 5m
   ```

### Benchmark Report Generation

Generate and analyze benchmark reports:

```bash
lemline benchmark report --output benchmark-results.html
lemline benchmark compare --baseline baseline.json --current current.json --output comparison.html
```

## Performance Optimization Techniques

### Identifying Bottlenecks

Use the following techniques to identify performance bottlenecks:

1. **Flame Graphs for Execution Path Analysis**:
   ```bash
   lemline profile --instance-id <instance-id> --format flamegraph --output profile.svg
   ```

2. **Critical Path Analysis**:
   ```bash
   lemline analyze critical-path --instance-id <instance-id>
   ```

3. **Performance Regression Testing**:
   ```bash
   lemline benchmark compare --baseline v1.0.json --current v2.0.json
   ```

### Common Optimization Patterns

#### Workflow-Level Optimizations

1. **Parallelization with Fork Nodes**:
   ```yaml
   - parallelProcessing:
       fork:
         - processA:
             do:
               - stepA1: {...}
               - stepA2: {...}
         - processB:
             do:
               - stepB1: {...}
               - stepB2: {...}
   ```

2. **Batching for Network Operations**:
   ```yaml
   - processBatch:
       for:
         in: ${ chunk(.items, 10) }  # Process items in batches of 10
         each: batch
       do:
         - processBatchItems: {...}
   ```

3. **Conditional Execution to Skip Unnecessary Work**:
   ```yaml
   - conditionalProcessing:
       if: ${ .needsProcessing == true }
       then:
         do:
           - heavyProcessing: {...}
   ```

#### Node-Level Optimizations

1. **Timeout Configuration to Prevent Hanging**:
   ```yaml
   - callExternalService:
       call: http
       with:
         timeout:
           seconds: 5
   ```

2. **Caching Expensive Operations**:
   ```yaml
   - getData:
       try:
         - checkCache:
             call: http
             with:
               method: get
               endpoint: https://cache.example/data/${.key}
       catch:
         errors:
           with:
             status: 404
         do:
           - fetchLiveData: {...}
           - updateCache: {...}
   ```

3. **Resource Pooling for Database Connections**:
   ```yaml
   lemline:
     resources:
       database-connection-pool:
         type: hikari
         min-connections: 10
         max-connections: 50
   ```

#### System-Level Optimizations

1. **Executor Pool Sizing**:
   ```yaml
   lemline:
     execution:
       worker-threads: 20
       max-concurrent-workflows: 100
   ```

2. **Memory Settings Optimization**:
   ```bash
   java -Xms2g -Xmx4g -XX:+UseG1GC -jar lemline-runner.jar
   ```

3. **Database Optimization for State Storage**:
   ```yaml
   lemline:
     database:
       batch-size: 50
       statement-timeout: 5s
       connection-timeout: 2s
   ```

## Performance Monitoring for Different Deployment Models

### Containerized Environments

For Kubernetes deployments, monitor performance with:

1. **Container Resource Metrics**:
   ```yaml
   metrics:
     - name: lemline_container_cpu_usage
       query: sum(rate(container_cpu_usage_seconds_total{pod=~"lemline.*"}[5m])) by (pod)
     - name: lemline_container_memory_usage
       query: sum(container_memory_usage_bytes{pod=~"lemline.*"}) by (pod)
   ```

2. **Horizontal Pod Autoscaler Metrics**:
   ```yaml
   apiVersion: autoscaling/v2beta2
   kind: HorizontalPodAutoscaler
   metadata:
     name: lemline-scaler
   spec:
     metrics:
     - type: Pods
       pods:
         metricName: lemline_workflow_queue_depth
         targetAverageValue: 10
   ```

### Serverless Deployments

For serverless deployments, focus on:

1. **Cold Start Metrics**:
   ```
   avg(lemline_cold_start_duration_milliseconds) by (function_name, region)
   ```

2. **Function Concurrency**:
   ```
   max(lemline_function_concurrent_executions) by (function_name)
   ```

3. **Memory Utilization**:
   ```
   max(lemline_function_memory_utilization) by (function_name)
   ```

## Performance Alerts and Notifications

Set up alerts for performance issues:

```yaml
alerts:
  - name: WorkflowExecutionSlowdown
    expr: histogram_quantile(0.95, sum(rate(lemline_workflow_execution_duration_bucket[15m])) by (le, namespace, name)) > 60000
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Workflow execution slowdown detected"
      description: "95th percentile of workflow execution time is above 60 seconds"

  - name: HighSystemLoad
    expr: sum(rate(process_cpu_seconds_total{job="lemline"}[1m])) * 100 > 80
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High system load detected"
      description: "CPU utilization is above 80% for the last 5 minutes"

  - name: WorkflowQueueBackpressure
    expr: sum(lemline_workflow_queue_depth) > 100
    for: 2m
    labels:
      severity: warning
    annotations:
      summary: "Workflow queue backpressure detected"
      description: "More than 100 workflows are pending execution"
```

## Performance Analysis Case Studies

### Case Study 1: HTTP Call Optimization

**Problem:** HTTP service calls causing workflow slowdowns

**Analysis Approach:**
1. Identify slow HTTP calls using node execution metrics
2. Analyze patterns in slow calls (specific endpoints, payload sizes)
3. Test connection pooling and timeout settings

**Solution:**
```yaml
lemline:
  http:
    client:
      connection-pool-size: 50
      connection-timeout: 2s
      socket-timeout: 5s
      max-connections-per-route: 20
      retry:
        max-attempts: 3
        backoff:
          initial-delay: 500ms
          multiplier: 2
```

### Case Study 2: Database Connection Bottlenecks

**Problem:** Database connection exhaustion during peak loads

**Analysis Approach:**
1. Monitor connection pool metrics
2. Correlate pool exhaustion with workflow throughput
3. Test various pool sizes and connection timeouts

**Solution:**
```yaml
lemline:
  database:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 2000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### Case Study 3: JVM Memory Tuning

**Problem:** Garbage collection pauses affecting workflow responsiveness

**Analysis Approach:**
1. Monitor GC metrics (frequency, duration)
2. Analyze heap usage patterns
3. Test different GC algorithms and memory settings

**Solution:**
```bash
java -Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
     -XX:ParallelGCThreads=8 -XX:ConcGCThreads=2 \
     -XX:InitiatingHeapOccupancyPercent=70 \
     -jar lemline-runner.jar
```

## Continuous Performance Monitoring

Implement continuous performance monitoring as part of your CI/CD pipeline:

1. **Benchmark Testing in CI**:
   ```yaml
   stages:
     - test
     - performance
   
   performance_test:
     stage: performance
     script:
       - lemline benchmark run --workflows-file workflows.yml --output benchmark.json
       - lemline benchmark compare --baseline baseline.json --current benchmark.json --threshold 10
   ```

2. **Performance Regression Alerts**:
   ```yaml
   alerts:
     - name: PerformanceRegression
       expr: increase(lemline_performance_regression_count[24h]) > 0
       labels:
         severity: warning
       annotations:
         summary: "Performance regression detected in CI pipeline"
   ```

3. **Daily Performance Reports**:
   ```bash
   0 0 * * * lemline report generate --period 24h --output /reports/daily-performance.html
   ```

## Best Practices for Performance Observability

1. **Establish Baselines** - Create performance baselines for all critical workflows
2. **End-to-End Monitoring** - Monitor the entire workflow lifecycle, not just individual components
3. **Contextual Metadata** - Include business context in performance metrics (workflow purpose, priority)
4. **Regular Benchmarking** - Conduct regular benchmark tests to detect regressions early
5. **Multi-Dimensional Analysis** - Analyze performance across multiple dimensions (time, instance type, data volume)
6. **Resource Correlation** - Correlate workflow performance with system resource usage
7. **Trend Analysis** - Look for long-term performance trends, not just point-in-time issues
8. **SLA Monitoring** - Set and monitor Service Level Objectives (SLOs) for critical workflows
9. **Performance Budgets** - Establish performance budgets for new workflow development
10. **Documentation** - Document performance characteristics and optimization strategies

## Conclusion

Effective performance observability is essential for maintaining efficient and reliable workflow operations in Lemline. By systematically collecting, analyzing, and acting on performance metrics, you can identify bottlenecks, optimize resource usage, establish appropriate SLAs, and ensure your workflows meet their performance goals even as your system scales.

The combination of workflow-level, node-level, and system-level metrics provides a comprehensive view of performance characteristics, enabling both reactive troubleshooting and proactive optimization of your workflow-based applications.

For more details on other observability aspects, see:
- [Lifecycle Observability](lemline-observability-lifecycle.md)
- [I/O Observability](lemline-observability-io.md)
- [Sizing and Scaling](lemline-observability-sizing.md)