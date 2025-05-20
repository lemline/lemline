# How to Configure Observability

This guide explains how to configure and implement observability in Lemline to monitor, trace, and analyze workflow execution, performance, and behavior.

## When to Configure Observability

Set up observability in Lemline when:

- Monitoring workflow execution in production
- Troubleshooting workflow failures
- Analyzing workflow performance
- Tracking resource utilization
- Setting up alerts for anomalies
- Creating dashboards for operational insight
- Implementing distributed tracing across systems
- Maintaining audit trails for compliance

## Observability Components

Lemline's observability consists of four main components:

1. **Metrics** - Numerical data about workflow execution, performance, and resources
2. **Logging** - Detailed contextual information about workflow execution
3. **Tracing** - Distributed tracing for workflow execution across services
4. **Events** - Structured data about workflow lifecycle events

## Basic Observability Configuration

### Enabling Observability

Enable observability in your `application.yaml`:

```yaml
lemline:
  observability:
    enabled: true
    metrics:
      enabled: true
    logging:
      enabled: true
    tracing:
      enabled: true
    events:
      enabled: true
```

### Metrics Configuration

Configure metrics collection and export:

```yaml
lemline:
  observability:
    metrics:
      collection-interval: 15s
      enabled-categories:
        - workflow
        - node
        - resource
        - io
      export:
        prometheus:
          enabled: true
          port: 9090
          path: /metrics
        jmx:
          enabled: true
        otlp:
          enabled: true
          endpoint: https://otlp.example.com:4317
          protocol: grpc
```

### Logging Configuration

Configure logging format and destinations:

```yaml
lemline:
  observability:
    logging:
      format: json  # Options: json, text
      level:
        root: INFO
        com.lemline: INFO
        com.lemline.core.execution: DEBUG
      include:
        input-data: false  # Caution: may contain sensitive data
        output-data: false
        context-data: false
      masking:
        enabled: true
        patterns:
          - path: $.password
          - path: $.creditCard
          - path: $.*.token
      destination:
        console:
          enabled: true
        file:
          enabled: true
          path: /var/log/lemline/workflow.log
          max-size: 100MB
          max-files: 10
```

### Tracing Configuration

Configure distributed tracing:

```yaml
lemline:
  observability:
    tracing:
      service-name: lemline-workflow-engine
      sample-ratio: 0.1  # Sample 10% of traces
      propagation:
        b3: true
        w3c: true
      export:
        otlp:
          enabled: true
          endpoint: https://jaeger.example.com:4317
          protocol: grpc  # Options: grpc, http/protobuf, http/json
        zipkin:
          enabled: false
          endpoint: https://zipkin.example.com:9411/api/v2/spans
```

### Events Configuration

Configure workflow events export:

```yaml
lemline:
  observability:
    events:
      include-categories:
        - workflow.definition
        - workflow.instance
        - workflow.node
        - system
      exclude-events: []  # List specific events to exclude
      export:
        internal:  # Internal event processing
          enabled: true
        webhook:
          enabled: true
          endpoint: https://events.example.com/ingest
          retry:
            enabled: true
            max-attempts: 3
        message-broker:
          enabled: true
          broker: ${lemline.messaging.type}
          topic: lemline-events
```

## Setting Up Workflow Monitoring

### Essential Workflow Metrics

Configure essential workflow metrics:

```yaml
lemline:
  observability:
    metrics:
      workflow:
        duration:
          enabled: true
          percentiles: [0.5, 0.75, 0.95, 0.99]
        counts:
          started: true
          completed: true
          failed: true
        states:
          active: true
          suspended: true
          waiting: true
```

### Node Execution Metrics

Track execution metrics at the node level:

```yaml
lemline:
  observability:
    metrics:
      node:
        execution:
          duration: true
          counts: true
        categories:
          - http
          - database
          - messaging
          - expression
        sampling:
          strategy: fixed-rate
          rate: 0.1  # Sample 10% of node executions
```

### Resource Utilization Metrics

Monitor resource utilization:

```yaml
lemline:
  observability:
    metrics:
      resources:
        memory: true
        cpu: true
        threads: true
        connections:
          database: true
          http: true
```

## Setting Up Distributed Tracing

### Workflow Span Configuration

Configure workflow-level spans:

```yaml
lemline:
  observability:
    tracing:
      workflow:
        create-root-span: true
        include-attributes:
          namespace: true
          name: true
          version: true
          input: false  # Caution: may contain sensitive data
      node:
        create-spans: true
        include-attributes:
          position: true
          type: true
```

### External System Tracing

Configure tracing for external system calls:

```yaml
lemline:
  observability:
    tracing:
      external:
        http:
          enabled: true
          include-headers: true
          include-body: false
        database:
          enabled: true
          include-query: true
          include-parameters: false
        messaging:
          enabled: true
          include-message: false
```

### Trace Context Propagation

Configure trace context propagation:

```yaml
lemline:
  observability:
    tracing:
      propagation:
        outbound:
          http:
            enabled: true
            header-format: b3  # Options: b3, w3c, jaeger
          messaging:
            enabled: true
            property-name: traceContext
```

## Setting Up Structured Logging

### Log Enrichment

Enrich logs with contextual information:

```yaml
lemline:
  observability:
    logging:
      enrichment:
        workflow:
          instance-id: true
          namespace: true
          name: true
          version: true
        trace:
          trace-id: true
          span-id: true
        node:
          position: true
          type: true
        system:
          instance-id: true
          environment: ${lemline.environment}
```

### Log Correlation

Enable log correlation across systems:

```yaml
lemline:
  observability:
    logging:
      correlation:
        enabled: true
        id-field: correlation_id
        propagation:
          http:
            enabled: true
            header-name: X-Correlation-ID
          messaging:
            enabled: true
            property-name: correlationId
```

### Structured Log Format

Configure structured log format:

```yaml
lemline:
  observability:
    logging:
      json:
        include-stacktrace: true
        timestamp-format: ISO_OFFSET_DATE_TIME
        include-thread-name: true
        include-logger-name: true
```

## Setting Up Event Export

### Event Filtering

Configure event filtering:

```yaml
lemline:
  observability:
    events:
      filters:
        - type: include
          pattern: workflow.instance.*
        - type: exclude
          pattern: workflow.node.executing
```

### Event Transformation

Configure event transformation:

```yaml
lemline:
  observability:
    events:
      transform:
        include-context: true
        flatten-nested: false
        include-timestamp: true
        schemas:
          enabled: true
          source: inline  # Options: inline, registry
```

### Event Batching

Configure event batching for export:

```yaml
lemline:
  observability:
    events:
      export:
        webhook:
          batching:
            enabled: true
            size: 100
            interval: 5s
            max-size: 1MB
```

## Integration with Monitoring Systems

### Prometheus Integration

Configure Prometheus integration:

```yaml
lemline:
  observability:
    metrics:
      export:
        prometheus:
          enabled: true
          port: 9090
          authentication:
            enabled: false
          tls:
            enabled: false
          pushgateway:
            enabled: false
            endpoint: http://pushgateway:9091
            job-name: lemline
            push-interval: 10s
```

### Elasticsearch Integration

Configure logging to Elasticsearch:

```yaml
lemline:
  observability:
    logging:
      elasticsearch:
        enabled: true
        hosts:
          - https://elasticsearch.example.com:9200
        index: lemline-logs-%date{yyyy.MM.dd}
        authentication:
          username: ${ES_USERNAME}
          password: ${ES_PASSWORD}
```

### Jaeger Integration

Configure Jaeger tracing:

```yaml
lemline:
  observability:
    tracing:
      export:
        jaeger:
          enabled: true
          endpoint: https://jaeger.example.com:14250
          protocol: grpc
          authentication:
            enabled: false
```

### Grafana Cloud Integration

Configure integration with Grafana Cloud:

```yaml
lemline:
  observability:
    integration:
      grafana-cloud:
        enabled: true
        metrics:
          endpoint: ${GRAFANA_METRICS_URL}
          username: ${GRAFANA_USERNAME}
          password: ${GRAFANA_API_KEY}
        logs:
          endpoint: ${GRAFANA_LOGS_URL}
          username: ${GRAFANA_USERNAME}
          password: ${GRAFANA_API_KEY}
        traces:
          endpoint: ${GRAFANA_TRACES_URL}
          username: ${GRAFANA_USERNAME}
          password: ${GRAFANA_API_KEY}
```

## Setting Up Alerts

### Basic Alert Configuration

Configure basic alerting:

```yaml
lemline:
  observability:
    alerts:
      enabled: true
      destinations:
        - type: webhook
          url: https://alerts.example.com/webhook
        - type: email
          to: alerts@example.com
```

### Alert Rules

Configure alert rules:

```yaml
lemline:
  observability:
    alerts:
      rules:
        - name: high-error-rate
          description: "High workflow error rate"
          condition: "rate(lemline_workflow_failed_total[5m]) / rate(lemline_workflow_started_total[5m]) > 0.05"
          duration: 2m
          severity: warning
          annotations:
            summary: "High workflow error rate detected"
            description: "Error rate is above 5% for the last 5 minutes"
        - name: long-workflow-duration
          description: "Unusually long workflow duration"
          condition: "histogram_quantile(0.95, sum(rate(lemline_workflow_duration_seconds_bucket[10m])) by (le, namespace, name)) > 300"
          duration: 5m
          severity: warning
          annotations:
            summary: "Long workflow execution detected"
            description: "95th percentile of workflow duration is above 5 minutes"
```

### Alert Notification Templates

Configure alert notification templates:

```yaml
lemline:
  observability:
    alerts:
      templates:
        email:
          subject: "[{{ .Status | toUpper }}] {{ .Labels.alertname }}"
          body: |
            Alert: {{ .Labels.alertname }}
            Status: {{ .Status }}
            Severity: {{ .Labels.severity }}
            Description: {{ .Annotations.description }}
            Start: {{ .StartsAt }}
            End: {{ .EndsAt }}
```

## Setting Up Dashboards

### Predefined Dashboards

Configure predefined dashboards:

```yaml
lemline:
  observability:
    dashboards:
      enabled: true
      providers:
        - type: grafana
          url: https://grafana.example.com
          api-key: ${GRAFANA_API_KEY}
          folder: Lemline
      deploy:
        enabled: true
        on-startup: true
```

### Custom Dashboard Creation

Configure custom dashboard creation:

```yaml
lemline:
  observability:
    dashboards:
      custom:
        - name: workflow-overview
          description: "Overview of workflow executions"
          source: file:/dashboards/workflow-overview.json
        - name: resource-utilization
          description: "Resource utilization metrics"
          source: file:/dashboards/resource-utilization.json
```

## Working with Audit Logs

### Audit Log Configuration

Configure audit logging:

```yaml
lemline:
  observability:
    audit:
      enabled: true
      include:
        workflow:
          definition:
            create: true
            update: true
            delete: true
          instance:
            start: true
            complete: true
            fail: true
            cancel: true
        user:
          login: true
          logout: true
          actions: true
      storage:
        type: database  # Options: database, file, external
        retention:
          enabled: true
          days: 90
      format: json
```

### External Audit Log Export

Configure audit log export:

```yaml
lemline:
  observability:
    audit:
      export:
        enabled: true
        destination:
          type: webhook
          url: https://audit.example.com/logs
          authentication:
            type: bearer
            token: ${AUDIT_TOKEN}
```

## Working with Log Levels

### Dynamic Log Level Configuration

Configure dynamic log level adjustment:

```yaml
lemline:
  observability:
    logging:
      dynamic-levels:
        enabled: true
        endpoint: /actuator/loggers
        authentication:
          enabled: true
          role: ADMIN
      default-levels:
        com.lemline: INFO
        com.lemline.core.execution: INFO
        com.lemline.runner: INFO
        org.apache.kafka: WARN
```

### Contextual Logging

Configure contextual logging:

```yaml
lemline:
  observability:
    logging:
      contextual:
        enabled: true
        workflow:
          namespace.pattern: com.example.*
          level: DEBUG
        node:
          type: http
          level: DEBUG
```

## Advanced Observability Features

### Health Checks

Configure health checks:

```yaml
lemline:
  observability:
    health:
      enabled: true
      endpoint: /health
      include:
        database: true
        messaging: true
        workflow-engine: true
      probes:
        liveness:
          path: /health/liveness
        readiness:
          path: /health/readiness
```

### Sampling Strategies

Configure metric and trace sampling:

```yaml
lemline:
  observability:
    sampling:
      metrics:
        strategy: adaptive
        base-rate: 0.1
        burst:
          trigger: "system_cpu_usage > 0.8"
          rate: 0.5
      tracing:
        strategy: rate-limiting
        rate: 100  # traces per second
```

### Performance Profiling

Configure performance profiling:

```yaml
lemline:
  observability:
    profiling:
      enabled: true
      endpoint: /actuator/profiling
      authentication:
        enabled: true
        role: ADMIN
      storage:
        directory: /tmp/lemline/profiles
        retention:
          count: 10
```

## Common Issues and Solutions

### Missing Metrics

**Issue**: Metrics not appearing in monitoring system  
**Solution**: 
- Verify metrics export configuration
- Check connectivity to metrics system
- Ensure correct port and endpoint configuration
- Check for firewall or network issues

### Trace Correlation Issues

**Issue**: Distributed traces not connecting  
**Solution**:
- Verify propagation headers configuration
- Check trace sampling configuration
- Ensure service names are consistent
- Check trace exporter configuration

### Log Volume Problems

**Issue**: Excessive log volume  
**Solution**:
- Adjust log levels appropriately
- Configure sampling for high-volume logs
- Use contextual logging
- Implement log filtering

## Best Practices

1. **Start Simple** - Begin with basic metrics, then add complexity
2. **Consider Performance Impact** - Monitor the overhead of your observability solution
3. **Protect Sensitive Data** - Configure data masking for logs and traces
4. **Use Structured Logging** - Makes logs more searchable and analyzable
5. **Implement Correlation IDs** - For tracking requests across services
6. **Set Appropriate Retention** - Based on compliance and debugging needs
7. **Monitor the Monitors** - Set up alerts for observability system failures
8. **Document Metrics** - Maintain a catalog of metrics and their meaning
9. **Standardize Naming** - Use consistent naming conventions
10. **Test Observability** - Verify observability during chaos testing

## Related Information

- [Performance Monitoring](lemline-observability-performance.md)
- [Workflow Lifecycle Observability](lemline-observability-lifecycle.md)
- [I/O Observability](lemline-observability-io.md)
- [Scaling Configuration](lemline-howto-scaling.md)
- [Message Broker Configuration](lemline-howto-brokers.md)