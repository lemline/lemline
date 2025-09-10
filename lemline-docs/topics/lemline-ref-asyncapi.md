# AsyncAPI Reference

This reference documents all aspects of AsyncAPI support in Lemline, including configuration, features, and advanced usage.

## AsyncAPI Support Overview

Lemline provides comprehensive AsyncAPI integration, enabling workflows to:

1. **Publish Messages**: Send messages to message brokers based on AsyncAPI specifications
2. **Subscribe to Messages**: Receive and process messages from message brokers
3. **Model-Driven Messaging**: Use AsyncAPI schemas for message validation
4. **Message Correlation**: Match related messages using correlation strategies
5. **Multiple Message Brokers**: Support for Kafka, RabbitMQ, and other message brokers

## Basic AsyncAPI Operations

The `callAsyncAPI` task provides AsyncAPI integration for both publishing and subscribing:

### Publishing Messages

```yaml
- publishOrder:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      publish:
        channel: "orders"
        message:
          payload:
            orderId: "ORD-123"
            customer:
              id: "CUST-456"
              name: "John Doe"
            items:
              - productId: "PROD-789"
                quantity: 2
                price: 19.99
```

### Subscribing to Messages

```yaml
- processOrderUpdates:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      subscribe:
        channel: "order-updates"
        consume:
          amount: 10  # Consume up to 10 messages
```

## AsyncAPI Specification

### Specification Sources

Lemline can load AsyncAPI specifications from:

1. **URL**: Remote specification
   ```yaml
   api: "https://api.example.com/asyncapi.yaml"
   ```

2. **File Path**: Local specification file
   ```yaml
   api: "/path/to/asyncapi.yaml"
   ```

3. **Resource Catalog**: Reference to a catalog resource
   ```yaml
   api: "order-service"  # References a defined resource
   ```

### Specification Formats

Lemline supports both YAML and JSON formats for AsyncAPI specifications:

- AsyncAPI 2.0.x
- AsyncAPI 2.1.x
- AsyncAPI 2.2.x
- AsyncAPI 2.3.x
- AsyncAPI 2.4.x

### Specification Validation

AsyncAPI specifications are validated on loading:

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  validateSpecification: true  # Default
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

### Specification Caching

Specifications are cached for performance:

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  cacheSpecification: true  # Default
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

## Publishing Messages

### Basic Publishing

```yaml
- publishOrder:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      publish:
        channel: "orders"
        message:
          payload: "${ .order }"
```

### Message Key (for Kafka)

```yaml
- publishOrder:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      publish:
        channel: "orders"
        message:
          key: "${ .order.id }"  # Message key for partitioning
          payload: "${ .order }"
```

### Message Headers

```yaml
- publishOrder:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      publish:
        channel: "orders"
        message:
          headers:
            correlation-id: "${ uuid() }"
            source: "order-service"
            timestamp: "${ now() }"
          payload: "${ .order }"
```

### Message Validation

Messages are validated against the AsyncAPI schema:

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  publish:
    channel: "orders"
    validateMessage: true  # Default
    message:
      payload: "${ .order }"
```

### Publishing Options

#### Delivery Guarantee

```yaml
publish:
  channel: "orders"
  deliveryGuarantee: "at-least-once"  # Options: at-least-once, at-most-once, exactly-once
  message:
    payload: "${ .order }"
```

#### Message Priority

```yaml
publish:
  channel: "orders"
  priority: 5  # Higher priority (0-9)
  message:
    payload: "${ .order }"
```

#### Message Expiration

```yaml
publish:
  channel: "orders"
  expiration: PT1H  # Expire after 1 hour if not consumed
  message:
    payload: "${ .order }"
```

#### Persistence

```yaml
publish:
  channel: "orders"
  persistent: true  # Ensure message is stored persistently
  message:
    payload: "${ .order }"
```

## Subscribing to Messages

### Basic Subscription

```yaml
- processOrderUpdates:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      subscribe:
        channel: "order-updates"
        consume:
          amount: 10  # Consume up to 10 messages
```

### Consumption Patterns

#### Fixed Amount

```yaml
subscribe:
  channel: "order-updates"
  consume:
    amount: 5  # Consume exactly 5 messages
```

#### Time-based

```yaml
subscribe:
  channel: "order-updates"
  consume:
    for: PT1M  # Consume messages for 1 minute
```

#### Condition-based

```yaml
subscribe:
  channel: "order-updates"
  consume:
    until: "${ .stopProcessing }"  # Consume until condition becomes true
```

#### Combined Patterns

```yaml
subscribe:
  channel: "order-updates"
  consume:
    amount: 100
    for: PT5M
    until: "${ .stopProcessing }"
    # Will stop when ANY condition is met
```

### Message Processing

#### Collect All Messages

```yaml
subscribe:
  channel: "order-updates"
  consume:
    amount: 10
    collect: true  # Collect all messages in an array
```

#### Process Messages Individually

```yaml
subscribe:
  channel: "order-updates"
  consume:
    amount: 10
    collect: false  # Default
    onMessage:
      set:
        lastOrder: "${ $message.payload }"  # Store each message
        orderCount: "${ .orderCount + 1 }"  # Count messages
```

#### Iterator Pattern

```yaml
subscribe:
  channel: "order-updates"
  consume:
    amount: 10
    forEach:
      as: "order"  # Variable name for current message
      do:
        - processOrder:
            # Process each order message
            set:
              processedOrders: "${ append(.processedOrders, .order) }"
```

### Message Acknowledgment

```yaml
subscribe:
  channel: "order-updates"
  consume:
    amount: 10
    autoAck: false  # Don't auto-acknowledge
    forEach:
      as: "order"
      do:
        - processOrder:
            # Process order
        - acknowledgeMessage:
            extension:
              ack: "${ .order }"  # Explicitly acknowledge
```

### Message Filtering

```yaml
subscribe:
  channel: "order-updates"
  filter: "${ .payload.status == 'SHIPPED' }"  # Only process shipped orders
  consume:
    amount: 10
```

## Message Correlation

### Basic Correlation

```yaml
- awaitOrderConfirmation:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      subscribe:
        channel: "order-confirmations"
        correlate:
          property: "orderId"
          value: "${ .order.id }"
        consume:
          amount: 1
```

### Header Correlation

```yaml
correlate:
  header: "correlation-id"
  value: "${ .correlationId }"
```

### Multiple Correlation Criteria

```yaml
correlate:
  all:
    - property: "orderId"
      value: "${ .order.id }"
    - property: "customerId"
      value: "${ .customer.id }"
```

### Expression-Based Correlation

```yaml
correlate:
  expression: "${ $message.payload.orderId == .order.id && $message.payload.status == 'CONFIRMED' }"
```

## Server Selection

### Default Server

By default, Lemline uses the first server in the AsyncAPI specification:

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

### Specific Server

Select a specific server by URL or name:

```yaml
# By URL
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  server: "kafka://kafka.example.com:9092"
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"

# By name
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  server: "production"  # Server name from specification
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

### Server Variables

Provide values for server variables:

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  server: "kafka://{host}:{port}"
  serverVariables:
    host: "kafka-prod.example.com"
    port: "9092"
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

## Channel Selection

### Direct Channel

```yaml
publish:
  channel: "orders"  # Direct channel name
```

### Dynamic Channel

```yaml
publish:
  channel: "${ .orderType + '-orders' }"  # Dynamic channel name
```

### Channel Parameters

```yaml
publish:
  channel: "orders/{region}"
  channelParameters:
    region: "us-west"
```

## Authentication

### Server Authentication

#### Username/Password

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  auth:
    basic:
      username: "producer"
      password:
        secret: "kafka.password"
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

#### API Key

```yaml
auth:
  apiKey:
    headerName: "X-API-Key"
    key:
      secret: "messaging.api.key"
```

#### SASL (for Kafka)

```yaml
auth:
  extension:
    sasl:
      mechanism: "PLAIN"  # or SCRAM-SHA-256, SCRAM-SHA-512
      username: "producer"
      password:
        secret: "kafka.password"
```

#### OAuth2

```yaml
auth:
  oauth2:
    grantType: "client_credentials"
    tokenUrl: "https://auth.example.com/token"
    clientId: "message-producer"
    clientSecret:
      secret: "oauth.client.secret"
    scopes:
      - "message:publish"
```

#### Certificate-Based

```yaml
auth:
  extension:
    certificate:
      keystore:
        path:
          secret: "messaging.keystore.path"
        password:
          secret: "messaging.keystore.password"
        alias: "client-cert"
```

## Security Configuration

### TLS/SSL Configuration

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  tls:
    enabled: true
    truststore:
      path:
        secret: "messaging.truststore.path"
      password:
        secret: "messaging.truststore.password"
    keystore:
      path:
        secret: "messaging.keystore.path"
      password:
        secret: "messaging.keystore.password"
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

## Advanced Features

### Batch Publishing

```yaml
- publishOrders:
    callAsyncAPI:
      api: "https://api.example.com/asyncapi.yaml"
      publish:
        channel: "orders"
        batch: true  # Enable batch publishing
        messages: "${ .orders }"  # Array of messages to publish
```

### Message Schemas

AsyncAPI schemas are used for validation:

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  publish:
    channel: "orders"
    message:
      contentType: "application/json"  # Can be overridden
      schemaFormat: "application/schema+json"  # Can be overridden
      payload: "${ .order }"
```

### Retry Behavior

Message operations can automatically retry on failure:

```yaml
- publishOrder:
    try:
      retry:
        policy:
          strategy: backoff
          backoff:
            delay: PT1S
            multiplier: 2
          limit:
            attempt:
              count: 3
      do:
        - sendOrder:
            callAsyncAPI:
              api: "https://api.example.com/asyncapi.yaml"
              publish:
                channel: "orders"
                message:
                  payload: "${ .order }"
```

### Message Broker Configuration

#### Kafka-Specific Configuration

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  extension:
    kafka:
      clientId: "lemline-producer"
      acks: "all"  # Options: "0", "1", "all"
      compression: "gzip"  # Options: none, gzip, snappy, lz4
      batchSize: 16384
      lingerMs: 1
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

#### RabbitMQ-Specific Configuration

```yaml
callAsyncAPI:
  api: "https://api.example.com/asyncapi.yaml"
  extension:
    rabbitmq:
      exchangeType: "topic"  # Options: direct, topic, fanout, headers
      durable: true
      exclusive: false
      autoDelete: false
      queueTtl: PT24H
  publish:
    channel: "orders"
    message:
      payload: "${ .order }"
```

## Catalog Integration

### AsyncAPI Catalog Definition

Define AsyncAPIs in the catalog:

```yaml
use:
  catalogs:
    - name: "messaging"
      resources:
        - name: "order-service"
          type: "asyncapi"
          location: "https://api.example.com/asyncapi.yaml"
```

### Using Catalog AsyncAPIs

Reference catalog AsyncAPIs in operations:

```yaml
- publishOrder:
    callAsyncAPI:
      api: "order-service"  # Reference to catalog resource
      publish:
        channel: "orders"
        message:
          payload: "${ .order }"
```

## AsyncAPI Client Configuration

Global AsyncAPI client configuration:

```properties
# Specification handling
lemline.asyncapi.cache-enabled=true
lemline.asyncapi.cache-max-size=100
lemline.asyncapi.cache-expiry=PT1H

# Validation settings
lemline.asyncapi.validate-specification=true
lemline.asyncapi.validate-message=true

# Default timeouts
lemline.asyncapi.connection-timeout=PT5S
lemline.asyncapi.operation-timeout=PT30S

# Messaging defaults
lemline.asyncapi.default-delivery-guarantee=at-least-once
lemline.asyncapi.default-auto-ack=true
```

## Use Cases and Examples

### Publish-Subscribe Pattern

```yaml
- processOrderFlow:
    do:
      - publishOrder:
          callAsyncAPI:
            api: "order-service"
            publish:
              channel: "new-orders"
              message:
                key: "${ .order.id }"
                payload: "${ .order }"
            as: "publishResult"
      
      - waitForConfirmation:
          callAsyncAPI:
            api: "order-service"
            subscribe:
              channel: "order-confirmations"
              correlate:
                property: "orderId"
                value: "${ .order.id }"
              consume:
                amount: 1
                timeout: PT1M
            as: "confirmation"
```

### Message Processing Pipeline

```yaml
- processMessages:
    callAsyncAPI:
      api: "order-service"
      subscribe:
        channel: "new-orders"
        consume:
          amount: 10
          forEach:
            as: "order"
            do:
              - validateOrder:
                  if: "${ .order.payload.total <= 0 }"
                  raise:
                    error: "invalidOrder"
                    with:
                      details: "Order total must be positive"
              
              - processOrder:
                  set:
                    processedOrder:
                      id: "${ .order.payload.id }"
                      status: "PROCESSING"
                      timestamp: "${ now() }"
              
              - publishResult:
                  callAsyncAPI:
                    api: "order-service"
                    publish:
                      channel: "processed-orders"
                      message:
                        key: "${ .processedOrder.id }"
                        payload: "${ .processedOrder }"
```

### Event Sourcing Pattern

```yaml
- handleCommand:
    do:
      - validateCommand:
          # Validation logic
      
      - generateEvent:
          set:
            event:
              type: "OrderCreated"
              data: "${ .command.data }"
              metadata:
                timestamp: "${ now() }"
                userId: "${ .command.userId }"
      
      - publishEvent:
          callAsyncAPI:
            api: "event-store"
            publish:
              channel: "events"
              message:
                key: "${ .event.data.id }"
                payload: "${ .event }"
```

### Streaming Data Processing

```yaml
- processSensorData:
    callAsyncAPI:
      api: "iot-platform"
      subscribe:
        channel: "sensor-readings"
        consume:
          for: PT1H
          forEach:
            as: "reading"
            do:
              - processReading:
                  # Process each reading
                  set:
                    processedReading:
                      sensorId: "${ .reading.payload.sensorId }"
                      value: "${ .reading.payload.value * 1.8 + 32 }"  # Convert to Fahrenheit
                      timestamp: "${ .reading.payload.timestamp }"
              
              - detectAnomaly:
                  if: "${ .processedReading.value > 100 }"
                  do:
                    - raiseAlert:
                        callAsyncAPI:
                          api: "iot-platform"
                          publish:
                            channel: "alerts"
                            message:
                              payload:
                                type: "HighTemperature"
                                sensorId: "${ .processedReading.sensorId }"
                                value: "${ .processedReading.value }"
                                timestamp: "${ .processedReading.timestamp }"
```

## Related Resources

- [Event-Driven Architecture](lemline-explain-event-driven.md)
- [Message Broker Configuration](lemline-howto-brokers.md)
- [Authentication Reference](lemline-ref-auth.md)
- [Resilience Patterns](dsl-resilience-patterns.md)