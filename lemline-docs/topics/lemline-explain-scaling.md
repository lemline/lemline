# Understanding Scaling in Lemline

This document explains how Lemline scales to handle varying workloads, from single-instance deployments to large-scale distributed systems.

## Scaling Fundamentals

Lemline is designed with scalability as a core principle, enabling it to handle:

1. **Increasing workflow volume**: More workflow instances
2. **Complex workflows**: Larger, more intricate workflow definitions
3. **Higher throughput**: More operations per second
4. **Growing data**: Larger datasets processed through workflows
5. **Extended scope**: More integrated systems and services

## Scaling Dimensions

Lemline can scale across multiple dimensions:

### Vertical Scaling (Scale Up)

Increasing resources on a single instance:

- **CPU**: More processing power for computation-intensive workflows
- **Memory**: Larger heap for more concurrent workflows
- **I/O**: Faster disk and network for data-intensive operations

Configuration example:

```properties
# JVM settings for vertical scaling
lemline.jvm.memory.min=1G
lemline.jvm.memory.max=4G
lemline.jvm.gc.strategy=G1GC
lemline.execution.thread-pool.size=100
```

### Horizontal Scaling (Scale Out)

Adding more instances to distribute the load:

- **Instance replication**: Multiple Lemline instances working together
- **Load balancing**: Distributing requests across instances
- **Workload partitioning**: Dividing work between instances

Configuration example:

```properties
# Cluster settings for horizontal scaling
lemline.cluster.enabled=true
lemline.cluster.provider=kubernetes
lemline.cluster.node-id=${POD_NAME}
lemline.cluster.discovery=kubernetes
lemline.cluster.namespace=lemline
```

### Functional Scaling (Scale Specialized)

Dedicated instances for specific functions:

- **Workflow executors**: Focused on running workflow logic
- **Event processors**: Dedicated to event processing
- **API handlers**: Specialized for API interactions
- **Database handlers**: Optimized for database operations

Configuration example:

```properties
# Role-based scaling
lemline.node.roles=EXECUTOR,EVENT_PROCESSOR
lemline.executor.enabled=true
lemline.executor.max-workflows=1000
lemline.event-processor.enabled=true
lemline.event-processor.max-events=10000
```

## Scaling Architecture

Lemline's architecture enables scaling through these key components:

### Stateless Execution

Workflow execution is designed to be stateless between persistence points:

- **Checkpoint-based persistence**: State is saved at defined points
- **Resumable execution**: Workflows can continue from persistence points
- **Instance independence**: Any instance can process any workflow

### Distributed Coordination

Multiple instances coordinate using:

- **Shared database**: Central state storage
- **Message broker**: Event distribution and coordination
- **Distributed locking**: Prevent concurrent processing of the same workflow
- **Leader election**: Coordinate cluster-wide operations

### Event-Driven Design

The event-driven approach enables scalable, loosely-coupled systems:

- **Asynchronous processing**: Decouples components for better scaling
- **Event sourcing**: Capture all state changes as events
- **Event distribution**: Scale by distributing events across instances
- **Event correlation**: Match related events regardless of source

## Database Scaling

The database layer scales through:

### Connection Pooling

Efficient database connection management:

```properties
# Database connection pool settings
lemline.database.min-pool-size=10
lemline.database.max-pool-size=50
lemline.database.idle-timeout=PT30M
lemline.database.max-lifetime=PT1H
```

### Read/Write Splitting

Separate read and write operations:

```properties
# Read/write splitting
lemline.database.read-write-splitting=true
lemline.database.write.url=jdbc:postgresql://primary.db:5432/lemline
lemline.database.read.urls=jdbc:postgresql://replica1.db:5432/lemline,jdbc:postgresql://replica2.db:5432/lemline
```

### Sharding

Partition data across multiple database instances:

```properties
# Database sharding
lemline.database.sharding.enabled=true
lemline.database.sharding.strategy=WORKFLOW_ID
lemline.database.sharding.nodes=3
```

### Query Optimization

Optimize database access patterns:

```properties
# Query optimization
lemline.database.batch-size=100
lemline.database.prepared-statement-cache-size=250
lemline.database.query-timeout=PT30S
```

## Messaging System Scaling

The messaging system scales through:

### Topic Partitioning

Distribute event processing across partitions:

```properties
# Kafka topic partitioning
lemline.messaging.kafka.topic-partitions=16
lemline.messaging.kafka.partition-strategy=WORKFLOW_ID
```

### Consumer Groups

Parallel event processing with consumer groups:

```properties
# Consumer group scaling
lemline.messaging.consumer-group=lemline-workers
lemline.messaging.consumers-per-topic=5
lemline.messaging.max-poll-records=500
```

### Message Batching

Batch messages for higher throughput:

```properties
# Message batching
lemline.messaging.producer.batch-size=16384
lemline.messaging.producer.linger-ms=5
lemline.messaging.consumer.batch-processing=true
```

### Back Pressure

Control flow when systems are overloaded:

```properties
# Back pressure handling
lemline.messaging.back-pressure.enabled=true
lemline.messaging.back-pressure.max-outstanding-requests=1000
lemline.messaging.back-pressure.max-request-rate=5000
```

## Workflow Execution Scaling

Workflow execution scales through:

### Parallel Task Execution

Execute tasks in parallel when possible:

```yaml
# Parallel execution with fork
- processInParallel:
    fork:
      branches:
        - branch1:
            # Task definition
        - branch2:
            # Task definition
        - branch3:
            # Task definition
```

### Task Batching

Process multiple items in a single operation:

```yaml
# Batch processing with for
- processBatch:
    for:
      iterator: "${ .items | _chunk(100) }"
      as: "batch"
      parallel: true
      maxConcurrency: 10
      do:
        - processBatch:
            callHTTP:
              url: "https://api.example.com/batch-process"
              method: "POST"
              body: "${ .batch }"
```

### Execution Pools

Configure separate execution pools for different types of tasks:

```properties
# Execution pools
lemline.execution.pools.http.core-size=20
lemline.execution.pools.http.max-size=100
lemline.execution.pools.compute.core-size=10
lemline.execution.pools.compute.max-size=50
lemline.execution.pools.io.core-size=30
lemline.execution.pools.io.max-size=150
```

### Prioritization

Prioritize critical workflows:

```properties
# Workflow prioritization
lemline.execution.prioritization.enabled=true
lemline.execution.prioritization.levels=5
lemline.execution.prioritization.default-level=3
```

## Kubernetes Scaling

In Kubernetes environments, Lemline scales using:

### Horizontal Pod Autoscaling

```yaml
# Horizontal Pod Autoscaler
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: lemline
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lemline
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  - type: Pods
    pods:
      metric:
        name: lemline_workflow_queue_length
      target:
        type: AverageValue
        averageValue: 100
```

### Custom Metrics Autoscaling

Lemline exposes metrics for custom scaling:

```properties
# Custom metrics for autoscaling
lemline.metrics.autoscaling.enabled=true
lemline.metrics.autoscaling.workflow-queue-length=true
lemline.metrics.autoscaling.event-queue-length=true
lemline.metrics.autoscaling.active-workflows=true
```

### Resource Configuration

Properly configured resource requests and limits:

```yaml
# Container resources
resources:
  requests:
    cpu: 500m
    memory: 1Gi
  limits:
    cpu: 2000m
    memory: 4Gi
```

### Affinity and Anti-Affinity

Optimize Lemline instance placement:

```yaml
# Pod affinity/anti-affinity
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 100
      podAffinityTerm:
        labelSelector:
          matchExpressions:
          - key: app
            operator: In
            values:
            - lemline
        topologyKey: "kubernetes.io/hostname"
```

## Load Balancing

Distribute traffic across instances:

### API Load Balancing

Balance API traffic:

```properties
# API load balancing
lemline.api.load-balancing.enabled=true
lemline.api.load-balancing.strategy=LEAST_CONNECTIONS
```

### Workflow Distribution

Distribute workflow processing:

```properties
# Workflow distribution
lemline.workflow.distribution.strategy=CONSISTENT_HASH
lemline.workflow.distribution.affinity.enabled=true
```

### Event Consumption

Balance event consumption:

```properties
# Event load balancing
lemline.events.consumer-strategy=COMPETING_CONSUMERS
lemline.events.consumer-count=5
```

## Caching

Caching improves performance under high load:

### Workflow Definition Caching

Cache workflow definitions:

```properties
# Workflow definition cache
lemline.cache.workflow-definitions.enabled=true
lemline.cache.workflow-definitions.max-size=1000
lemline.cache.workflow-definitions.ttl=PT1H
```

### Expression Caching

Cache evaluated expressions:

```properties
# Expression caching
lemline.cache.expressions.enabled=true
lemline.cache.expressions.max-size=10000
lemline.cache.expressions.ttl=PT5M
```

### HTTP Response Caching

Cache HTTP responses:

```properties
# HTTP response caching
lemline.http.cache.enabled=true
lemline.http.cache.max-size=1000
lemline.http.cache.ttl=PT5M
```

### Distributed Caching

Configure a distributed cache for multi-instance setups:

```properties
# Distributed caching
lemline.cache.distributed.enabled=true
lemline.cache.distributed.provider=hazelcast
lemline.cache.distributed.hazelcast.members=lemline-cache-1:5701,lemline-cache-2:5701
```

## Monitoring & Scaling Triggers

Monitor performance to inform scaling decisions:

### Key Metrics

Track key metrics for scaling:

1. **Workflow Queue Length**: Number of pending workflows
2. **Event Queue Length**: Number of pending events
3. **Active Workflows**: Number of currently executing workflows
4. **Workflow Latency**: Time to complete workflows
5. **Resource Utilization**: CPU, memory, and I/O usage

```properties
# Scaling metrics
lemline.metrics.scaling.enabled=true
lemline.metrics.scaling.collection-interval=PT15S
```

### Alerting and Triggers

Set alerts and triggers for scaling events:

```properties
# Auto-scaling triggers
lemline.autoscaling.triggers.workflow-queue-threshold=100
lemline.autoscaling.triggers.event-queue-threshold=500
lemline.autoscaling.triggers.latency-threshold=PT5S
```

## Scaling Scenarios

### Low-Volume Scenario

Single-instance configuration for low workload:

```properties
# Low-volume configuration
lemline.jvm.memory.max=1G
lemline.execution.thread-pool.size=20
lemline.database.max-pool-size=10
lemline.messaging.consumers-per-topic=1
```

### Medium-Volume Scenario

Multi-instance configuration for medium workload:

```properties
# Medium-volume configuration
lemline.cluster.enabled=true
lemline.jvm.memory.max=4G
lemline.execution.thread-pool.size=100
lemline.database.max-pool-size=30
lemline.messaging.consumers-per-topic=3
```

### High-Volume Scenario

Distributed configuration for high workload:

```properties
# High-volume configuration
lemline.cluster.enabled=true
lemline.node.roles=EXECUTOR,EVENT_PROCESSOR
lemline.jvm.memory.max=8G
lemline.execution.thread-pool.size=200
lemline.database.sharding.enabled=true
lemline.database.max-pool-size=50
lemline.messaging.kafka.topic-partitions=32
lemline.messaging.consumers-per-topic=10
lemline.cache.distributed.enabled=true
```

## Performance Tuning

Fine-tune the system for optimal scaling:

### JVM Tuning

Optimize JVM settings:

```properties
# JVM performance tuning
lemline.jvm.gc.strategy=G1GC
lemline.jvm.gc.max-gc-pause-millis=200
lemline.jvm.memory.direct-memory-size=1G
lemline.jvm.compiler-threshold=10000
```

### Thread Pool Tuning

Optimize thread pools:

```properties
# Thread pool tuning
lemline.execution.thread-pool.core-size=50
lemline.execution.thread-pool.max-size=200
lemline.execution.thread-pool.keep-alive=PT60S
lemline.execution.thread-pool.queue-size=1000
lemline.execution.thread-pool.rejection-policy=CALLER_RUNS
```

### Database Tuning

Optimize database access:

```properties
# Database tuning
lemline.database.statement-cache-size=500
lemline.database.batch-size=100
lemline.database.fetch-size=1000
lemline.database.transaction-isolation=READ_COMMITTED
```

### Messaging Tuning

Optimize messaging:

```properties
# Messaging performance tuning
lemline.messaging.kafka.producer.compression=snappy
lemline.messaging.kafka.producer.acks=1
lemline.messaging.kafka.producer.batch-size=65536
lemline.messaging.kafka.producer.linger-ms=10
lemline.messaging.kafka.consumer.fetch-min-bytes=1024
lemline.messaging.kafka.consumer.fetch-max-wait-ms=500
```

## Scaling Limits and Boundaries

Understand system limits:

### Known Limits

- **Maximum workflows per instance**: Depends on workflow complexity and resources
- **Maximum events per second**: Depends on messaging system configuration
- **Maximum concurrent tasks**: Limited by thread pool settings
- **Maximum data size**: Limited by memory and database constraints

### Breaking the Limits

Strategies for exceeding default limits:

1. **Workflow Partitioning**: Break large workflows into smaller, linked workflows
2. **Data Streaming**: Process large datasets as streams rather than batches
3. **Hierarchical Workflows**: Use parent/child workflow structures
4. **External Processing**: Offload heavy computation to external systems
5. **Event-Based Decomposition**: Decompose processing using event chains

## Scaling Best Practices

1. **Start Small, Scale Incrementally**: Begin with a small deployment and scale as needed
2. **Monitor Before Scaling**: Understand performance bottlenecks before scaling
3. **Test Scaling Scenarios**: Test different scaling configurations
4. **Balance Resources**: Ensure balanced scaling across all components
5. **Automate Scaling**: Use automatic scaling based on metrics
6. **Consider Data Locality**: Process data close to its storage location
7. **Design for Horizontal Scaling**: Prefer horizontal scaling over vertical when possible
8. **Optimize Before Scaling**: Fix performance issues before adding resources
9. **Consider Cost vs. Performance**: Balance cost and performance needs
10. **Plan for Failure**: Design scaling to handle component failures

## Related Resources

- [Configuration Reference](lemline-ref-config.md)
- [Monitoring Workflows](lemline-howto-monitor.md)
- [Database Configuration](lemline-howto-brokers.md)
- [Performance Monitoring](lemline-observability-performance.md)
- [Resource Sizing Guide](lemline-observability-sizing.md)