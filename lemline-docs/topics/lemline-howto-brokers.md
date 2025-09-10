# How to Configure Message Brokers

This guide explains how to configure and use message brokers with Lemline for event-driven workflows, asynchronous communication, and reliable message processing.

## When to Use Message Brokers

Use message brokers in Lemline when:

- Implementing event-driven workflows
- Creating asynchronous communication between systems
- Building resilient, loosely-coupled architectures
- Processing messages with guaranteed delivery
- Implementing pub/sub communication patterns
- Managing high-throughput message processing
- Connecting Lemline to existing event-based systems

## Supported Message Brokers

Lemline provides built-in support for these message brokers:

- **Kafka** - High-throughput distributed messaging system
- **RabbitMQ** - Robust message broker implementing AMQP
- **In-Memory** - For testing and development (not for production use)

## Basic Configuration

### General Broker Configuration

Configure the messaging system in your `application.yaml`:

```yaml
lemline:
  messaging:
    type: kafka  # Options: kafka, rabbitmq, in-memory
    health-check:
      enabled: true
      interval: 30s
    reconnect:
      enabled: true
      max-attempts: 5
      backoff:
        initial-delay: 1s
        multiplier: 2
        max-delay: 30s
```

### Kafka Configuration

For Kafka integration:

```yaml
lemline:
  messaging:
    type: kafka
    kafka:
      bootstrap-servers: localhost:9092
      client-id: lemline-workflow-engine
      security:
        protocol: PLAINTEXT  # Options: PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL
      producer:
        acks: all
        retries: 3
        batch-size: 16384
        linger-ms: 5
        buffer-memory: 33554432
      consumer:
        group-id: lemline-consumers
        auto-offset-reset: earliest
        enable-auto-commit: false
        max-poll-records: 500
        session-timeout-ms: 30000
      admin:
        auto-create-topics: true
      topics:
        workflow-events: lemline-workflow-events
        workflow-commands: lemline-workflow-commands
```

### RabbitMQ Configuration

For RabbitMQ integration:

```yaml
lemline:
  messaging:
    type: rabbitmq
    rabbitmq:
      host: localhost
      port: 5672
      username: guest
      password: guest
      virtual-host: /
      connection-timeout: 5000
      publisher-confirms: true
      publisher-returns: true
      automatic-recovery: true
      retry:
        enabled: true
        initial-interval: 1000
        max-attempts: 3
        multiplier: 2.0
      exchanges:
        workflow-events:
          name: lemline.workflow.events
          type: topic
          durable: true
        workflow-commands:
          name: lemline.workflow.commands
          type: direct
          durable: true
      queues:
        workflow-events:
          name: lemline.workflow.events
          durable: true
          arguments:
            x-dead-letter-exchange: lemline.workflow.events.dlx
        workflow-commands:
          name: lemline.workflow.commands
          durable: true
          arguments:
            x-dead-letter-exchange: lemline.workflow.commands.dlx
```

## Configuring Event Producers

### Basic Event Publishing

Configure how Lemline publishes events:

```yaml
lemline:
  messaging:
    producer:
      type: ${lemline.messaging.type}
      batch-size: 100
      delivery-guarantee: at-least-once  # Options: at-most-once, at-least-once, exactly-once
      timeout: 5s
      retry:
        enabled: true
        max-attempts: 3
        backoff:
          initial-delay: 100ms
          multiplier: 2
          max-delay: 1s
```

### Publishing Strategies

Configure different publishing strategies:

```yaml
lemline:
  messaging:
    producer:
      strategies:
        critical-events:
          delivery-guarantee: exactly-once
          timeout: 10s
          retry:
            max-attempts: 5
        bulk-events:
          batch-size: 1000
          linger: 100ms
          compression: true
```

## Configuring Event Consumers

### Basic Event Consumption

Configure how Lemline consumes events:

```yaml
lemline:
  messaging:
    consumer:
      type: ${lemline.messaging.type}
      concurrency: 5  # Number of concurrent consumers
      batch-size: 100
      poll-timeout: 5s
      retry:
        enabled: true
        max-attempts: 3
        backoff:
          initial-delay: 500ms
          multiplier: 2
          max-delay: 5s
```

### Consumer Error Handling

Configure error handling for consumers:

```yaml
lemline:
  messaging:
    consumer:
      error-handling:
        strategy: retry-then-dlq  # Options: retry-then-dlq, always-dlq, discard, log-only
        dlq-suffix: .dlq
        poisonous-message-detection:
          enabled: true
          max-consecutive-failures: 3
```

## Setting Up Topic/Exchange Mappings

Configure how workflow events map to topics or exchanges:

```yaml
lemline:
  messaging:
    mappings:
      workflow-events:
        topic: lemline-workflow-events  # Kafka topic or RabbitMQ exchange
        routing-key-template: workflow.event.{type}  # For RabbitMQ
      workflow-commands:
        topic: lemline-workflow-commands
        routing-key-template: workflow.command.{type}
      custom-events:
        topic: lemline-custom-events
        routing-key-template: workflow.custom.{type}
```

## Implementing Event-Driven Workflows

### Emitting Events

Emit events from within workflows:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: order-workflow
  version: '0.1.0'
do:
  - processOrder:
      # Other steps...
  - notifyShipping:
      emit:
        event:
          with:
            type: order.shipped
            source: order-service
            data:
              orderId: ${ .orderId }
              trackingNumber: ${ .trackingNumber }
```

### Listening for Events

Configure workflows to listen for events:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: shipping-workflow
  version: '0.1.0'
schedule:
  on:
    one:
      with:
        type: order.shipped
do:
  - processShippingNotification:
      # Process the shipping notification
```

## Advanced Broker Configuration

### Kafka Advanced Configuration

For advanced Kafka settings:

```yaml
lemline:
  messaging:
    kafka:
      advanced:
        producer:
          compression-type: lz4
          transaction-timeout-ms: 60000
          transaction-id-prefix: lemline-tx-
          key-serializer: org.apache.kafka.common.serialization.StringSerializer
          value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
        consumer:
          fetch-min-bytes: 1
          fetch-max-bytes: 52428800
          heartbeat-interval-ms: 3000
          key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
          value-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
        ssl:
          keystore-location: /path/to/client.keystore.jks
          keystore-password: ${KEYSTORE_PASSWORD}
          truststore-location: /path/to/client.truststore.jks
          truststore-password: ${TRUSTSTORE_PASSWORD}
        sasl:
          mechanism: PLAIN  # Options: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512
          jaas-config: org.apache.kafka.common.security.plain.PlainLoginModule required username="${KAFKA_USERNAME}" password="${KAFKA_PASSWORD}";
```

### RabbitMQ Advanced Configuration

For advanced RabbitMQ settings:

```yaml
lemline:
  messaging:
    rabbitmq:
      advanced:
        connection:
          requested-channel-max: 0
          requested-heartbeat: 60
          topology-recovery: true
          frame-max: 0
          connection-timeout: 60000
          handshake-timeout: 10000
          shutdown-timeout: 10000
        cache:
          channel:
            size: 25
            checkout-timeout: 0
        listener:
          simple:
            prefetch: 250
            transaction-size: 1
            default-requeue-rejected: true
            idle-event-interval: 30000
            missing-queues-fatal: false
        ssl:
          enabled: false
          key-store: /path/to/client.keystore.p12
          key-store-password: ${KEYSTORE_PASSWORD}
          trust-store: /path/to/client.truststore.p12
          trust-store-password: ${TRUSTSTORE_PASSWORD}
          algorithm: TLSv1.2
```

## Event Serialization and Schema Management

### Configuring Event Serialization

Configure serialization formats:

```yaml
lemline:
  messaging:
    serialization:
      format: json  # Options: json, avro, protobuf
      include-schema: true
      compression: true
      json:
        date-format: ISO_OFFSET_DATE_TIME
      avro:
        schema-registry:
          url: http://localhost:8081
          auto-register-schemas: true
```

### Schema Evolution Support

Configure schema evolution handling:

```yaml
lemline:
  messaging:
    schema:
      evolution:
        strategy: backward  # Options: backward, forward, full
        validation:
          enabled: true
          on-error: fail  # Options: fail, warn, ignore
      registry:
        type: confluent  # Options: confluent, apicurio, custom
        url: http://localhost:8081
        auth:
          username: ${SCHEMA_REGISTRY_USERNAME}
          password: ${SCHEMA_REGISTRY_PASSWORD}
```

## Monitoring Message Broker Integration

Enable monitoring of message broker operations:

```yaml
lemline:
  messaging:
    monitoring:
      metrics:
        enabled: true
        export-jmx: true
      health:
        enabled: true
        include-detail: true
      tracing:
        enabled: true
        include-payload: false
      logging:
        enabled: true
        include-payload: false
        log-level: INFO
```

## Testing with the In-Memory Broker

For development and testing, use the in-memory broker:

```yaml
lemline:
  messaging:
    type: in-memory
    in-memory:
      persistence: false  # Whether to persist messages between restarts
      max-queue-size: 10000
      delivery-latency-ms: 0  # Simulated delivery latency
```

## Common Issues and Solutions

### Connection Issues

**Issue**: Cannot connect to message broker  
**Solution**: 
- Verify broker hostname and port
- Check network connectivity
- Verify credentials
- Ensure necessary permissions are granted

### Message Delivery Issues

**Issue**: Messages not being delivered  
**Solution**:
- Check topic/queue exists
- Verify producer configurations
- Check consumer group configuration
- Ensure serialization format matches consumer expectations

### Performance Issues

**Issue**: Slow message processing  
**Solution**:
- Increase consumer concurrency
- Adjust batch sizes
- Optimize message size
- Configure appropriate prefetch counts

## Best Practices

1. **Use Topic/Queue Conventions** - Develop consistent naming conventions
2. **Implement Dead Letter Queues** - For handling message processing failures
3. **Configure Appropriate Timeouts** - Set realistic timeouts for operations
4. **Monitor Broker Health** - Implement comprehensive monitoring
5. **Plan for Failure** - Design with broker outages in mind
6. **Use Schema Management** - Especially for evolving event structures
7. **Secure Broker Communications** - Use TLS and authentication
8. **Test Failure Scenarios** - Verify system behavior during broker outages
9. **Implement Idempotent Consumers** - Handle potential message duplicates
10. **Consider Message Ordering** - When ordering is important, design accordingly

## Related Information

- [Event Handling Guide](lemline-howto-events.md)
- [Observability Configuration](lemline-howto-observability.md)
- [Scaling Configuration](lemline-howto-scaling.md)
- [AsyncAPI Reference](lemline-ref-asyncapi.md)
- [Message Broker Performance](lemline-observability-performance.md)