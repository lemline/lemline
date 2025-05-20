# I/O Observability

This document explains how to monitor, analyze, and optimize input/output (I/O) operations in Lemline workflows. I/O operations, including network calls, database interactions, message processing, and file operations, often represent the most significant performance bottlenecks and failure points in workflow execution.

## Types of I/O in Lemline Workflows

Lemline workflows interact with various external systems, each with different I/O characteristics:

1. **HTTP/REST API Calls** - Synchronous calls to external HTTP endpoints
2. **Database Operations** - Reads and writes to relational or NoSQL databases
3. **Message Queue Operations** - Publishing to and consuming from message brokers
4. **File System Operations** - Reading from and writing to files
5. **gRPC Service Calls** - Synchronous calls to gRPC services
6. **Event Processing** - Waiting for and handling external events
7. **Container Execution** - Running containers as part of workflows

## Core I/O Metrics

Lemline provides comprehensive metrics for I/O operations:

### HTTP Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_http_request_duration_seconds` | HTTP request duration | endpoint, method, status_code |
| `lemline_http_request_size_bytes` | Size of HTTP request payloads | endpoint, method |
| `lemline_http_response_size_bytes` | Size of HTTP response payloads | endpoint, method |
| `lemline_http_request_count` | Count of HTTP requests | endpoint, method, status_code |
| `lemline_http_connection_errors` | Count of HTTP connection errors | endpoint, error_type |
| `lemline_http_timeout_count` | Count of HTTP request timeouts | endpoint, method |

### Database Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_db_operation_duration_seconds` | Database operation duration | operation_type, table |
| `lemline_db_query_count` | Count of database queries | operation_type, table |
| `lemline_db_row_count` | Number of rows affected/returned | operation_type, table |
| `lemline_db_transaction_duration_seconds` | Database transaction duration | - |
| `lemline_db_connection_count` | Active database connections | pool_name |
| `lemline_db_connection_wait_duration_seconds` | Wait time for database connections | pool_name |

### Message Queue Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_message_publish_duration_seconds` | Time to publish messages | destination, message_type |
| `lemline_message_consume_duration_seconds` | Time to consume messages | source, message_type |
| `lemline_message_size_bytes` | Size of messages | destination, message_type |
| `lemline_message_count` | Count of messages | destination, operation_type, message_type |
| `lemline_message_error_count` | Count of message errors | destination, error_type |
| `lemline_message_lag` | Consumer lag in messages | source, consumer_group |

### File System Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_file_operation_duration_seconds` | File operation duration | operation_type, file_type |
| `lemline_file_size_bytes` | Size of files read/written | operation_type, file_type |
| `lemline_file_operation_count` | Count of file operations | operation_type, file_type |
| `lemline_file_operation_errors` | Count of file operation errors | operation_type, error_type |

### Event Processing Metrics

| Metric | Description | Dimensions |
|--------|-------------|------------|
| `lemline_event_wait_duration_seconds` | Time waiting for events | event_type, source |
| `lemline_event_processing_duration_seconds` | Time processing events | event_type, source |
| `lemline_event_count` | Count of events received | event_type, source |
| `lemline_event_size_bytes` | Size of event payloads | event_type, source |
| `lemline_event_timeout_count` | Count of event wait timeouts | event_type, source |

## Setting Up I/O Monitoring

### Basic Configuration

Enable detailed I/O monitoring in your Lemline configuration:

```yaml
lemline:
  observability:
    io:
      enabled: true
      detailed-metrics: true
      sampling-rate: 1.0  # Sample 100% of I/O operations
      trace:
        enabled: true
      metrics:
        http: true
        database: true
        messaging: true
        filesystem: true
        events: true
```

### Integration with OpenTelemetry

For distributed tracing of I/O operations:

```yaml
lemline:
  observability:
    opentelemetry:
      enabled: true
      service-name: lemline-workflows
      exporter:
        type: otlp
        endpoint: https://telemetry.example.com:4317
      span-processors:
        batch:
          max-queue-size: 2048
          max-export-batch-size: 512
          scheduler-interval-millis: 5000
      instrumentation:
        io:
          enabled: true
```

### Log Configuration for I/O

Configure detailed logging for I/O operations:

```yaml
lemline:
  logging:
    io:
      enabled: true
      level: INFO  # Set to DEBUG for more verbose logging
      include-payload: false  # Set to true to include actual data (caution: may log sensitive data)
      include-headers: true
      max-payload-size: 1024  # Truncate large payloads
```

## I/O Visualization and Analysis

### Grafana Dashboard for I/O Monitoring

Create a Grafana dashboard with essential I/O panels:

1. **HTTP Request Duration by Endpoint**:
   ```
   histogram_quantile(0.95, sum(rate(lemline_http_request_duration_seconds_bucket{namespace="$namespace"}[5m])) by (le, endpoint))
   ```

2. **Database Operation Timing**:
   ```
   sum(rate(lemline_db_operation_duration_seconds_sum{namespace="$namespace"}[5m])) by (operation_type)
   /
   sum(rate(lemline_db_operation_duration_seconds_count{namespace="$namespace"}[5m])) by (operation_type)
   ```

3. **Message Processing Rate**:
   ```
   sum(rate(lemline_message_count{namespace="$namespace", operation_type="publish"}[5m])) by (destination)
   ```

4. **I/O Error Rate**:
   ```
   sum(rate(lemline_http_connection_errors{namespace="$namespace"}[5m]))
   +
   sum(rate(lemline_db_query_errors{namespace="$namespace"}[5m]))
   +
   sum(rate(lemline_message_error_count{namespace="$namespace"}[5m]))
   +
   sum(rate(lemline_file_operation_errors{namespace="$namespace"}[5m]))
   ```

5. **Event Wait Time**:
   ```
   histogram_quantile(0.95, sum(rate(lemline_event_wait_duration_seconds_bucket{namespace="$namespace"}[5m])) by (le, event_type))
   ```

### Advanced I/O Analysis Tools

For deeper I/O analysis:

1. **Trace Visualization**:
   Use Jaeger or Zipkin to visualize traces of I/O operations across distributed systems.

2. **Correlation Analysis**:
   ```sql
   -- SQL query to correlate HTTP call duration with workflow execution time
   SELECT 
     w.workflow_name,
     w.workflow_id,
     AVG(w.duration_ms) as avg_workflow_duration_ms,
     AVG(h.duration_ms) as avg_http_duration_ms,
     SUM(h.duration_ms) as total_http_duration_ms,
     ROUND(SUM(h.duration_ms) * 100.0 / AVG(w.duration_ms), 2) as http_percentage
   FROM workflow_executions w
   JOIN http_calls h ON h.workflow_id = w.workflow_id
   GROUP BY w.workflow_name, w.workflow_id
   ORDER BY http_percentage DESC
   LIMIT 10;
   ```

3. **I/O Activity Flame Graph**:
   ```bash
   lemline profile --instance-id <instance-id> --io-only --format flamegraph --output io-profile.svg
   ```

## I/O Optimization Techniques

### HTTP Optimization

1. **Connection Pooling**:
   ```yaml
   lemline:
     http:
       client:
         connection-pool-size: 50
         max-routes: 500
         connection-timeout: 2s
         socket-timeout: 10s
         max-connections-per-route: 20
   ```

2. **HTTP Request Compression**:
   ```yaml
   lemline:
     http:
       client:
         compression:
           enabled: true
           min-size: 1024  # Only compress payloads larger than 1KB
   ```

3. **HTTP Response Caching**:
   ```yaml
   lemline:
     http:
       client:
         cache:
           enabled: true
           max-size: 1000
           time-to-live: 5m
   ```

### Database Optimization

1. **Database Connection Pooling**:
   ```yaml
   lemline:
     database:
       hikari:
         maximum-pool-size: 50
         minimum-idle: 10
         idle-timeout: 600000
         max-lifetime: 1800000
   ```

2. **Batch Database Operations**:
   ```yaml
   - insertBatchRecords:
       call: database
       with:
         operation: batch-insert
         table: user_activities
         records: ${ .batch }
   ```

3. **Read/Write Splitting**:
   ```yaml
   lemline:
     database:
       read-replica:
         enabled: true
         replicas:
           - host: db-read-1.example.com
           - host: db-read-2.example.com
         strategy: round-robin
   ```

### Message Queue Optimization

1. **Message Batching**:
   ```yaml
   lemline:
     messaging:
       kafka:
         producer:
           batch-size: 16384
           linger-ms: 5
           compression-type: snappy
   ```

2. **Consumer Concurrency**:
   ```yaml
   lemline:
     messaging:
       kafka:
         consumer:
           concurrency: 5
           max-poll-records: 500
           auto-commit: false
   ```

3. **Message Filtering**:
   ```yaml
   - listenForEvents:
       listen:
         to:
           any:
             with:
               type: user.activity.*
               filter: '${ .priority == "high" }'
   ```

### Event Processing Optimization

1. **Timeout Configuration**:
   ```yaml
   - waitForUserApproval:
       listen:
         to:
           one:
             with:
               type: approval.granted
               correlationId: ${ .requestId }
         for:
           hours: 24  # Maximum wait time
   ```

2. **Event Correlation**:
   ```yaml
   lemline:
     events:
       correlation:
         keys:
           - orderId
           - requestId
           - sessionId
         max-stored-keys: 10000
         expiration: 24h
   ```

## I/O Patterns and Anti-Patterns

### Recommended I/O Patterns

1. **Circuit Breaker Pattern**:
   ```yaml
   lemline:
     resilience:
       circuit-breakers:
         external-api:
           failure-threshold: 50
           success-threshold: 3
           wait-duration: 30s
           timeout: 5s
   ```

2. **Retry with Backoff**:
   ```yaml
   - callExternalApi:
       try:
         - makeApiCall: {...}
       catch:
         errors:
           with:
             type: communication
         retry:
           delay:
             seconds: 1
           backoff:
             exponential:
               multiplier: 2
               max-delay:
                 seconds: 60
           limit:
             attempt:
               count: 5
   ```

3. **Bulkhead Pattern**:
   ```yaml
   lemline:
     resilience:
       bulkheads:
         database-operations:
           max-concurrent-calls: 20
           max-wait-duration: 2s
         http-operations:
           max-concurrent-calls: 50
           max-wait-duration: 1s
   ```

### I/O Anti-Patterns to Avoid

1. **Sequential API Calls** - Replace with parallel execution when possible:
   ```yaml
   # Anti-pattern: Sequential calls
   - callServiceA: {...}
   - callServiceB: {...}
   - callServiceC: {...}
   
   # Better pattern: Parallel calls
   - parallelCalls:
       fork:
         - serviceA: { do: [...] }
         - serviceB: { do: [...] }
         - serviceC: { do: [...] }
   ```

2. **Chatty I/O** - Replace with batch operations:
   ```yaml
   # Anti-pattern: Multiple individual operations
   - for:
       in: ${ .items }
       each: item
     do:
       - processItem: {...}
   
   # Better pattern: Batch processing
   - processBatch:
       call: http
       with:
         method: post
         endpoint: https://api.example/batch-process
         body: ${ .items }
   ```

3. **Polling** - Replace with event-driven approaches:
   ```yaml
   # Anti-pattern: Polling
   - checkStatus:
       while: ${ .status != "completed" }
       do:
         - sleep: { duration: { seconds: 5 } }
         - getStatus: {...}
   
   # Better pattern: Event-driven
   - waitForCompletion:
       listen:
         to:
           one:
             with:
               type: process.completed
               correlationId: ${ .processId }
   ```

## I/O Observability for Different Deployment Scenarios

### Microservices Environment

In a microservices architecture, focus on cross-service I/O:

1. **Service Dependency Tracking**:
   ```yaml
   lemline:
     observability:
       service-topology:
         enabled: true
         discovery:
           enabled: true
   ```

2. **Cross-Service Tracing**:
   ```yaml
   lemline:
     observability:
       distributed-tracing:
         propagation:
           b3: true
           w3c: true
   ```

### Cloud Environment

For cloud deployments, monitor cloud service I/O:

1. **Cloud API Metrics**:
   ```yaml
   lemline:
     observability:
       cloud:
         aws:
           enabled: true
           services:
             - s3
             - dynamodb
             - lambda
         gcp:
           enabled: true
           services:
             - storage
             - firestore
             - functions
   ```

2. **Cloud Resource Tagging**:
   ```yaml
   lemline:
     observability:
       resource-attributes:
         cloud.provider: aws
         cloud.region: us-west-2
         deployment.environment: production
   ```

## I/O Alerting and Notifications

Set up alerts for I/O issues:

```yaml
alerts:
  - name: HighHttpErrorRate
    expr: sum(rate(lemline_http_request_count{status_code=~"5.."}[5m])) / sum(rate(lemline_http_request_count[5m])) > 0.05
    for: 2m
    labels:
      severity: warning
    annotations:
      summary: "High HTTP error rate detected"
      description: "HTTP error rate is above 5% for the last 5 minutes"

  - name: SlowDatabaseQueries
    expr: histogram_quantile(0.95, sum(rate(lemline_db_operation_duration_seconds_bucket[5m])) by (le, operation_type)) > 1.0
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Slow database queries detected"
      description: "95th percentile of database query time is above 1 second"

  - name: MessageQueueLag
    expr: max(lemline_message_lag) > 1000
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High message queue lag detected"
      description: "Message queue consumer lag is above 1000 messages"
```

## I/O Analysis Case Studies

### Case Study 1: HTTP Timeouts and Retries

**Problem:** Workflows failing due to intermittent HTTP timeouts

**Analysis Approach:**
1. Analyze HTTP timeout metrics by endpoint
2. Review HTTP connection patterns
3. Test different timeout and retry configurations

**Solution:**
```yaml
lemline:
  http:
    client:
      connect-timeout: 2s
      socket-timeout: 10s
      retry:
        max-attempts: 3
        statuses:
          - 408  # Request Timeout
          - 429  # Too Many Requests
          - 503  # Service Unavailable
        methods:
          - GET
          - HEAD
        backoff:
          delay: 200ms
          multiplier: 2
          max-delay: 2s
```

### Case Study 2: Database Connection Bottlenecks

**Problem:** Workflows blocked waiting for database connections

**Analysis Approach:**
1. Monitor connection pool metrics (usage, wait time)
2. Analyze connection usage patterns across workflows
3. Review database operation durations

**Solution:**
```yaml
lemline:
  database:
    hikari:
      maximum-pool-size: 50
      connection-timeout: 5000
      validation-timeout: 2000
      metrics:
        enabled: true
    statement-timeout: 5s
    operations:
      logging:
        threshold: 1000ms
```

### Case Study 3: Message Processing Backlog

**Problem:** Message queue consumers falling behind during peak loads

**Analysis Approach:**
1. Monitor message lag metrics
2. Analyze message processing rates
3. Identify message processing bottlenecks

**Solution:**
```yaml
lemline:
  messaging:
    kafka:
      consumers:
        concurrency: 5
        auto-scaling:
          enabled: true
          min: 2
          max: 10
          target-utilization: 0.7
      producer:
        batch-size: 16384
        linger-ms: 10
        compression-type: lz4
```

## Advanced I/O Observability

### Data Flow Analysis

Track data flow through your system:

```yaml
lemline:
  observability:
    data-flow:
      enabled: true
      tracking:
        payload-hashing: true
        correlation-id-propagation: true
      visualization:
        enabled: true
```

### I/O Rate Limiting

Configure rate limiting to prevent I/O overload:

```yaml
lemline:
  rate-limiting:
    http:
      default:
        limit: 100
        window: 1s
      endpoints:
        "api.example.com":
          limit: 50
          window: 1s
    database:
      write-operations:
        limit: 500
        window: 1s
```

### I/O Security Monitoring

Monitor for I/O security issues:

```yaml
lemline:
  observability:
    security:
      enabled: true
      sensitive-data-detection: true
      auth-failure-tracking: true
      unusual-pattern-detection: true
```

## Best Practices for I/O Observability

1. **Categorize I/O Operations** - Group I/O by type, criticality, and expected performance
2. **Set Baselines** - Establish performance baselines for common I/O operations
3. **Track End-to-End** - Monitor complete I/O flows, not just individual operations
4. **Correlation IDs** - Use correlation IDs to track related I/O operations
5. **Contextual Logging** - Include business context in I/O logs
6. **Payload Sampling** - Sample I/O payloads for debugging (while respecting privacy)
7. **Dependency Mapping** - Maintain a map of external system dependencies
8. **Synthetic Monitoring** - Use synthetic transactions to monitor external system health
9. **Circuit Breakers** - Implement circuit breakers for unreliable external systems
10. **SLA Monitoring** - Define and monitor SLAs for critical I/O operations

## Conclusion

Effective I/O observability is crucial for maintaining reliable and efficient workflow operations in Lemline. By systematically monitoring, analyzing, and optimizing I/O operations, you can identify bottlenecks, prevent cascading failures, set appropriate timeouts and retries, and ensure your workflows interact efficiently with external systems.

The combination of comprehensive metrics, distributed tracing, and intelligent alerting provides a complete view of I/O behavior, enabling both reactive troubleshooting and proactive optimization of your workflow-based applications.

For more details on other observability aspects, see:
- [Lifecycle Observability](lemline-observability-lifecycle.md)
- [Performance Observability](lemline-observability-performance.md)
- [Sizing and Scaling](lemline-observability-sizing.md)