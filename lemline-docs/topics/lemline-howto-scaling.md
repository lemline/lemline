# How to Configure Scaling

This guide explains how to configure and implement scaling in Lemline to handle varying workloads efficiently and ensure optimal performance.

## When to Configure Scaling

Configure scaling in Lemline when:

- Handling varying workflow volumes
- Processing high-throughput event streams
- Managing peak load periods
- Optimizing resource utilization
- Implementing high-availability deployments
- Controlling costs while maintaining performance
- Preparing for production deployment
- Handling seasonal or time-based traffic patterns

## Types of Scaling in Lemline

Lemline supports multiple scaling approaches:

1. **Vertical Scaling** - Adjusting resources (CPU, memory) for the Lemline instances
2. **Horizontal Scaling** - Adding or removing Lemline instances
3. **Worker Pool Scaling** - Adjusting the number of worker threads within instances
4. **Database Connection Scaling** - Managing database connection pools
5. **Event Consumer Scaling** - Scaling message consuming threads

## Basic Scaling Configuration

### Core Scaling Configuration

Configure basic scaling in your `application.yaml`:

```yaml
lemline:
  scaling:
    enabled: true
    strategy: automatic  # Options: automatic, manual, scheduled
    metrics-collection-interval: 15s
    target-cpu-utilization: 70
    target-memory-utilization: 70
    scaling-interval: 1m
    cooldown-period: 5m
```

### Worker Thread Pool Configuration

Configure worker thread pool scaling:

```yaml
lemline:
  scaling:
    worker-pool:
      enabled: true
      min-size: 10
      max-size: 100
      queue-capacity: 1000
      keep-alive-time: 60s
      core-thread-timeout: true  # Whether core threads can timeout
      scaling:
        enabled: true
        strategy: queue-size  # Options: queue-size, utilization
        target-queue-size: 50  # Scale when queue reaches this size
        scale-increment: 5  # Add/remove threads in increments of 5
```

### Database Connection Pool Scaling

Configure database connection pool scaling:

```yaml
lemline:
  scaling:
    database:
      connection-pool:
        min-size: 5
        max-size: 50
        idle-timeout: 5m
        max-lifetime: 30m
        scaling:
          enabled: true
          strategy: demand  # Options: demand, fixed
          target-utilization: 0.7  # Scale when utilization reaches 70%
```

## Horizontal Scaling

### Manual Horizontal Scaling

Configure manual horizontal scaling:

```yaml
lemline:
  scaling:
    horizontal:
      enabled: true
      strategy: manual
      min-instances: 2
      max-instances: 10
      instance-type: medium  # Depends on deployment platform
```

### Automatic Horizontal Scaling

Configure automatic horizontal scaling:

```yaml
lemline:
  scaling:
    horizontal:
      enabled: true
      strategy: automatic
      min-instances: 2
      max-instances: 10
      scale-up:
        metrics:
          - type: cpu-utilization
            threshold: 70  # Percentage
            comparison: ">="
            duration: 3m
          - type: workflow-queue-depth
            threshold: 100  # Number of queued workflows
            comparison: ">="
            duration: 1m
        increment: 1  # Add this many instances
        cooldown-period: 5m
      scale-down:
        metrics:
          - type: cpu-utilization
            threshold: 30  # Percentage
            comparison: "<="
            duration: 10m
          - type: workflow-queue-depth
            threshold: 10  # Number of queued workflows
            comparison: "<="
            duration: 10m
        decrement: 1  # Remove this many instances
        cooldown-period: 15m
```

### Scheduled Horizontal Scaling

Configure scheduled horizontal scaling:

```yaml
lemline:
  scaling:
    horizontal:
      enabled: true
      strategy: scheduled
      schedules:
        - cron: "0 8 * * MON-FRI"  # Weekdays at 8 AM
          instances: 10
          description: "Business hours scaling"
        - cron: "0 18 * * MON-FRI"  # Weekdays at 6 PM
          instances: 2
          description: "After business hours scaling"
        - cron: "0 8 * * SAT-SUN"  # Weekends at 8 AM
          instances: 2
          description: "Weekend scaling"
```

## Deployment-Specific Scaling

### Kubernetes Scaling

Configure scaling for Kubernetes deployments:

```yaml
lemline:
  scaling:
    kubernetes:
      horizontal-pod-autoscaler:
        enabled: true
        min-replicas: 2
        max-replicas: 10
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
      vertical-pod-autoscaler:
        enabled: false
        update-mode: Auto  # Options: Auto, Off, Initial
        resource-policy:
          container-policies:
            - container-name: lemline
              min-allowed:
                cpu: 100m
                memory: 512Mi
              max-allowed:
                cpu: 4
                memory: 8Gi
```

### Cloud Provider Scaling

Configure scaling for cloud provider deployments:

```yaml
lemline:
  scaling:
    cloud:
      aws:
        auto-scaling-group:
          enabled: true
          min-size: 2
          max-size: 10
          desired-capacity: 2
          cooldown-period: 300  # seconds
        scaling-policies:
          - name: ScaleUpOnHighCPU
            adjustment-type: ChangeInCapacity
            scaling-adjustment: 1
            cooldown: 300
            alarm:
              metric-name: CPUUtilization
              namespace: AWS/EC2
              statistic: Average
              period: 60
              evaluation-periods: 3
              threshold: 70
              comparison-operator: GreaterThanOrEqualToThreshold
          - name: ScaleDownOnLowCPU
            adjustment-type: ChangeInCapacity
            scaling-adjustment: -1
            cooldown: 300
            alarm:
              metric-name: CPUUtilization
              namespace: AWS/EC2
              statistic: Average
              period: 60
              evaluation-periods: 15
              threshold: 30
              comparison-operator: LessThanOrEqualToThreshold
```

## Event Consumer Scaling

### Basic Consumer Scaling

Configure event consumer scaling:

```yaml
lemline:
  scaling:
    event-consumers:
      enabled: true
      min-consumers: 1
      max-consumers: 10
      scaling:
        strategy: rate  # Options: rate, lag
        target-processing-rate: 500  # messages per second
        scale-increment: 1
        cooldown-period: 60s
```

### Advanced Consumer Scaling

Configure advanced consumer scaling for specific event types:

```yaml
lemline:
  scaling:
    event-consumers:
      consumers:
        - event-type: order.created
          min-consumers: 2
          max-consumers: 20
          priority: high
          scaling:
            strategy: lag
            target-lag: 100  # Maximum message lag
            scale-increment: 2
        - event-type: notification.sent
          min-consumers: 1
          max-consumers: 5
          priority: low
          scaling:
            strategy: rate
            target-processing-rate: 100
            scale-increment: 1
```

## Resource-Aware Workflow Execution

### Resource Estimation

Configure resource estimation for workflows:

```yaml
lemline:
  scaling:
    resource-estimation:
      enabled: true
      default:
        cpu-units: 0.1
        memory-mb: 50
      workflows:
        - namespace: "data-processing"
          name: "*"
          cpu-units: 0.5
          memory-mb: 200
        - namespace: "notification"
          name: "*"
          cpu-units: 0.05
          memory-mb: 20
```

### Workflow Priorities

Configure workflow priorities for execution:

```yaml
lemline:
  scaling:
    priorities:
      enabled: true
      levels:
        - name: high
          weight: 100
          resources:
            min-cpu: 0.5
            min-memory: 512
          queue:
            max-size: 1000
        - name: normal
          weight: 50
          resources:
            min-cpu: 0.2
            min-memory: 256
          queue:
            max-size: 5000
        - name: low
          weight: 10
          resources:
            min-cpu: 0.1
            min-memory: 128
          queue:
            max-size: 10000
      workflow-mappings:
        - namespace: "payment"
          name: "*"
          priority: high
        - namespace: "reporting"
          name: "*"
          priority: low
```

## High Availability Configuration

### Basic HA Configuration

Configure high availability:

```yaml
lemline:
  high-availability:
    enabled: true
    min-instances: 2
    leader-election:
      enabled: true
      strategy: database  # Options: database, zookeeper, kubernetes
    state-replication:
      enabled: true
      strategy: database  # Options: database, shared-filesystem
```

### Clustering Configuration

Configure clustering for high availability:

```yaml
lemline:
  clustering:
    enabled: true
    cluster-name: lemline-cluster
    discovery:
      strategy: kubernetes  # Options: kubernetes, dns, config
      kubernetes:
        namespace: default
        labels:
          app: lemline
    node-coordination:
      enabled: true
      heartbeat-interval: 5s
      failure-detection-timeout: 15s
    load-balancing:
      strategy: consistent-hash  # Options: round-robin, consistent-hash, least-connections
      workflow-affinity: true  # Prefer routing related workflows to the same node
```

## Load Management

### Rate Limiting

Configure rate limiting to prevent overload:

```yaml
lemline:
  scaling:
    rate-limiting:
      enabled: true
      workflow-starts:
        limit: 100
        window: 1s
        exceed-policy: delay  # Options: delay, reject
      api-requests:
        limit: 1000
        window: 1s
        exceed-policy: reject
```

### Backpressure Handling

Configure backpressure handling:

```yaml
lemline:
  scaling:
    backpressure:
      enabled: true
      queue-threshold: 80  # Percentage
      strategies:
        - type: delay
          threshold: 80
          delay-ms: 100
        - type: reject
          threshold: 95
          rejection-message: "System under heavy load, please try again later"
```

## Monitoring Scaling Behavior

### Scaling Metrics

Configure scaling metrics collection:

```yaml
lemline:
  scaling:
    metrics:
      enabled: true
      include:
        worker-pool-size: true
        worker-pool-utilization: true
        connection-pool-size: true
        connection-pool-utilization: true
        instance-count: true
        queue-sizes: true
        scaling-events: true
      export:
        prometheus: true
```

### Scaling Events

Configure scaling event notifications:

```yaml
lemline:
  scaling:
    events:
      enabled: true
      include:
        worker-pool-scaling: true
        connection-pool-scaling: true
        instance-scaling: true
      destinations:
        - type: log
          level: INFO
        - type: webhook
          url: https://monitors.example.com/events
        - type: message-broker
          topic: lemline-scaling-events
```

## Advanced Scaling Features

### Predictive Scaling

Configure predictive scaling based on historical patterns:

```yaml
lemline:
  scaling:
    predictive:
      enabled: true
      algorithm: linear-regression  # Options: linear-regression, arima, lstm
      training-data-window: 7d  # Use 7 days of historical data
      prediction-horizon: 1h  # Predict 1 hour ahead
      confidence-threshold: 0.7  # Minimum confidence to apply prediction
      update-frequency: 1h  # Update predictions hourly
```

### Custom Scaling Metrics

Configure custom metrics for scaling decisions:

```yaml
lemline:
  scaling:
    custom-metrics:
      enabled: true
      metrics:
        - name: business-activity-level
          source: prometheus
          query: sum(rate(order_created_total[5m]))
          threshold: 1000
          comparison: ">="
          duration: 2m
          action: scale-up
          instances: 2
```

### Multi-Dimensional Scaling

Configure scaling based on multiple metrics:

```yaml
lemline:
  scaling:
    multi-dimensional:
      enabled: true
      algorithm: weighted-average  # Options: weighted-average, max, min
      dimensions:
        - metric: cpu-utilization
          weight: 0.4
          threshold: 70
        - metric: memory-utilization
          weight: 0.2
          threshold: 80
        - metric: workflow-queue-depth
          weight: 0.4
          threshold: 100
      threshold: 0.7  # Aggregate threshold for scaling decision
```

## Best Practices for Scaling

### Resource Allocation Guidelines

Follow these guidelines for resource allocation:

```yaml
lemline:
  scaling:
    resource-guidelines:
      workflows-per-cpu: 20  # Target workflows per CPU core
      memory-per-workflow: 50MB  # Average memory per workflow
      database-connections-per-instance: 10  # Target DB connections per instance
      max-workflow-concurrency-per-instance: 100  # Max concurrent workflows per instance
```

### Scaling Strategy by Environment

Configure different scaling strategies by environment:

```yaml
lemline:
  scaling:
    environments:
      development:
        strategy: fixed
        instances: 1
        worker-threads: 10
      testing:
        strategy: fixed
        instances: 2
        worker-threads: 20
      staging:
        strategy: scheduled
        min-instances: 2
        max-instances: 5
      production:
        strategy: automatic
        min-instances: 3
        max-instances: 20
```

## Common Issues and Solutions

### Scaling Lag

**Issue**: System doesn't scale quickly enough for sudden traffic spikes  
**Solution**: 
- Reduce cooldown periods
- Increase scale-up increments
- Implement predictive scaling
- Set appropriate thresholds for early detection

### Resource Starvation

**Issue**: Workflows compete for limited resources  
**Solution**:
- Implement workflow prioritization
- Set appropriate resource limits per workflow
- Configure backpressure handling
- Increase minimum resource allocation

### Scaling Instability

**Issue**: System scales up and down rapidly (thrashing)  
**Solution**:
- Increase cooldown periods
- Set different thresholds for scale-up and scale-down
- Implement hysteresis in scaling decisions
- Use time-weighted average metrics instead of instantaneous values

## Deployment-Specific Configuration Examples

### Kubernetes Deployment

```yaml
# Horizontal Pod Autoscaler (HPA)
apiVersion: autoscaling/v2beta2
kind: HorizontalPodAutoscaler
metadata:
  name: lemline-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lemline
  minReplicas: 2
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

### AWS Deployment

```yaml
# Auto Scaling Group with scheduled actions
Resources:
  LemlineAutoScalingGroup:
    Type: AWS::AutoScaling::AutoScalingGroup
    Properties:
      MinSize: 2
      MaxSize: 10
      DesiredCapacity: 2
      LaunchTemplate:
        LaunchTemplateId: !Ref LemlineLaunchTemplate
        Version: !GetAtt LemlineLaunchTemplate.LatestVersionNumber
      ScheduledActions:
        - ScheduledActionName: ScaleUpForBusinessHours
          Recurrence: "0 8 * * MON-FRI"
          MinSize: 5
          MaxSize: 10
          DesiredCapacity: 5
        - ScheduledActionName: ScaleDownAfterBusinessHours
          Recurrence: "0 18 * * MON-FRI"
          MinSize: 2
          MaxSize: 5
          DesiredCapacity: 2
```

## Related Information

- [Performance Monitoring](lemline-observability-performance.md)
- [Sizing and Scaling Observability](lemline-observability-sizing.md)
- [Observability Configuration](lemline-howto-observability.md)
- [Message Broker Configuration](lemline-howto-brokers.md)
- [Configuration Reference](lemline-ref-config.md)