# Connections Reference

This reference documents all aspects of connection management in Lemline, including configuration, reuse, and advanced features.

## Connections Overview

The Connections feature in Lemline provides a way to define, configure, and reuse connection configurations for various services and protocols:

1. **Connection Definition**: Centralized configuration of endpoints and properties
2. **Connection Reuse**: Reference connections across multiple tasks
3. **Connection Management**: Runtime management of connection pools and resources
4. **Connection Security**: Consistent security configurations
5. **Connection Monitoring**: Track and observe connection health and metrics

## Connection Types

Lemline supports connections for various protocols and services:

| Connection Type | Description | Default Port |
|-----------------|-------------|--------------|
| `http` | HTTP/HTTPS connections | 80/443 |
| `grpc` | gRPC service connections | 50051 |
| `kafka` | Kafka message broker | 9092 |
| `rabbitmq` | RabbitMQ message broker | 5672 |
| `database` | Database connections | Varies |
| `redis` | Redis key-value store | 6379 |
| `custom` | User-defined connection types | N/A |

## Connection Definition

### Basic Connection

Connections are defined in the `use.connections` section of workflows:

```yaml
use:
  connections:
    - name: "userService"
      type: "http"
      endpoint: "https://api.example.com/users"
      timeout: PT30S
```

### Connection Properties

Common properties for all connection types:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `name` | String | Unique connection identifier | Required |
| `type` | String | Connection type | Required |
| `endpoint` | String | Base URL or connection string | Required |
| `timeout` | Duration | Connection timeout | PT30S |
| `retryable` | Boolean | Whether connection supports retries | true |
| `pooling` | Boolean | Enable connection pooling | true |
| `maxConnections` | Integer | Maximum concurrent connections | 10 |
| `idleTimeout` | Duration | Connection idle timeout | PT60S |
| `validateOnBorrow` | Boolean | Validate connection before use | true |
| `metadata` | Object | Custom metadata for the connection | {} |

## HTTP Connections

HTTP connections define configurations for making HTTP requests:

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      timeout: PT30S
      headers:
        User-Agent: "Lemline/1.0"
        Accept: "application/json"
      followRedirects: true
      compression: true
      maxRedirects: 5
      connectTimeout: PT5S
      readTimeout: PT30S
      writeTimeout: PT30S
```

Properties specific to HTTP connections:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `headers` | Object | Default headers for all requests | {} |
| `followRedirects` | Boolean | Automatically follow redirects | true |
| `compression` | Boolean | Enable request/response compression | true |
| `maxRedirects` | Integer | Maximum number of redirects to follow | 5 |
| `connectTimeout` | Duration | Socket connection timeout | PT5S |
| `readTimeout` | Duration | Socket read timeout | PT30S |
| `writeTimeout` | Duration | Socket write timeout | PT30S |
| `keepAlive` | Boolean | Enable HTTP keep-alive | true |
| `keepAliveDuration` | Duration | Keep-alive duration | PT60S |
| `http2` | Boolean | Enable HTTP/2 if supported | false |
| `proxy` | Object | HTTP proxy configuration | null |

## gRPC Connections

gRPC connections define configurations for making gRPC calls:

```yaml
use:
  connections:
    - name: "userGrpcService"
      type: "grpc"
      endpoint: "grpc://grpc.example.com:50051"
      timeout: PT30S
      maxInboundMessageSize: 4194304
      maxOutboundMessageSize: 4194304
      keepAliveTime: PT60S
      keepAliveTimeout: PT20S
      keepAliveWithoutCalls: false
      waitForReady: true
      loadBalancingPolicy: "round_robin"
```

Properties specific to gRPC connections:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `maxInboundMessageSize` | Integer | Maximum inbound message size in bytes | 4194304 |
| `maxOutboundMessageSize` | Integer | Maximum outbound message size in bytes | 4194304 |
| `keepAliveTime` | Duration | gRPC keep-alive time | PT60S |
| `keepAliveTimeout` | Duration | gRPC keep-alive timeout | PT20S |
| `keepAliveWithoutCalls` | Boolean | Send keep-alive pings without active calls | false |
| `waitForReady` | Boolean | Wait for server to be ready | false |
| `loadBalancingPolicy` | String | Load balancing policy | "pick_first" |
| `maxRetryAttempts` | Integer | Maximum number of retry attempts | 5 |
| `serviceConfig` | Object | gRPC service config | null |

## Messaging Connections

### Kafka Connection

```yaml
use:
  connections:
    - name: "orderStream"
      type: "kafka"
      endpoint: "kafka://kafka.example.com:9092"
      clientId: "lemline-producer"
      acks: "all"
      compression: "gzip"
      batchSize: 16384
      lingerMs: 1
      bufferMemory: 33554432
      maxRequestSize: 1048576
      receiveBufferBytes: 32768
      sendBufferBytes: 131072
      maxInFlightRequests: 5
      retries: 3
      retryBackoffMs: 100
```

Properties specific to Kafka connections:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `clientId` | String | Kafka client ID | "lemline" |
| `acks` | String | Producer acks setting | "all" |
| `compression` | String | Compression type | "none" |
| `batchSize` | Integer | Batch size in bytes | 16384 |
| `lingerMs` | Integer | Linger time in ms | 0 |
| `bufferMemory` | Integer | Buffer memory in bytes | 33554432 |
| `maxRequestSize` | Integer | Max request size in bytes | 1048576 |
| `receiveBufferBytes` | Integer | Socket receive buffer | 32768 |
| `sendBufferBytes` | Integer | Socket send buffer | 131072 |
| `maxInFlightRequests` | Integer | Max in-flight requests | 5 |
| `retries` | Integer | Number of retries | 0 |
| `retryBackoffMs` | Integer | Retry backoff in ms | 100 |

### RabbitMQ Connection

```yaml
use:
  connections:
    - name: "orderQueue"
      type: "rabbitmq"
      endpoint: "amqp://rabbitmq.example.com:5672"
      virtualHost: "/"
      username: "guest"
      password:
        secret: "rabbitmq.password"
      connectionTimeout: PT10S
      handshakeTimeout: PT10S
      shutdownTimeout: PT10S
      requestedHeartbeat: PT60S
      automaticRecovery: true
      topologyRecovery: true
      channelRpcTimeout: PT60S
      channelPoolSize: 5
```

Properties specific to RabbitMQ connections:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `virtualHost` | String | RabbitMQ virtual host | "/" |
| `username` | String | RabbitMQ username | "guest" |
| `password` | Secret | RabbitMQ password | "guest" |
| `connectionTimeout` | Duration | Connection timeout | PT10S |
| `handshakeTimeout` | Duration | Handshake timeout | PT10S |
| `shutdownTimeout` | Duration | Shutdown timeout | PT10S |
| `requestedHeartbeat` | Duration | Requested heartbeat | PT60S |
| `automaticRecovery` | Boolean | Enable automatic recovery | true |
| `topologyRecovery` | Boolean | Enable topology recovery | true |
| `channelRpcTimeout` | Duration | Channel RPC timeout | PT60S |
| `channelPoolSize` | Integer | Channel pool size | 5 |

## Database Connections

```yaml
use:
  connections:
    - name: "orderDb"
      type: "database"
      endpoint: "jdbc:postgresql://db.example.com:5432/orders"
      driver: "org.postgresql.Driver"
      username: "db_user"
      password:
        secret: "db.password"
      poolSize: 10
      maxLifetime: PT30M
      idleTimeout: PT10M
      validationQuery: "SELECT 1"
      validationTimeout: PT3S
      initializationFailTimeout: PT1M
      autoCommit: true
      transactionIsolation: "READ_COMMITTED"
```

Properties specific to database connections:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `driver` | String | JDBC driver class | Derived from URL |
| `username` | String | Database username | None |
| `password` | Secret | Database password | None |
| `poolSize` | Integer | Connection pool size | 10 |
| `maxLifetime` | Duration | Maximum connection lifetime | PT30M |
| `idleTimeout` | Duration | Connection idle timeout | PT10M |
| `validationQuery` | String | Connection validation query | "SELECT 1" |
| `validationTimeout` | Duration | Validation timeout | PT3S |
| `initializationFailTimeout` | Duration | Init failure timeout | PT1M |
| `autoCommit` | Boolean | Enable auto-commit | true |
| `transactionIsolation` | String | Transaction isolation level | "READ_COMMITTED" |

## Custom Connections

Custom connection types can be defined using the extension mechanism:

```yaml
use:
  connections:
    - name: "customService"
      type: "custom"
      extension:
        provider: "com.example.CustomConnectionProvider"
        properties:
          property1: "value1"
          property2: "value2"
```

## Connection Authentication

### Basic Authentication

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      auth:
        basic:
          username: "api_user"
          password:
            secret: "api.password"
```

### Bearer Token Authentication

```yaml
auth:
  bearer:
    token:
      secret: "api.token"
```

### OAuth2 Authentication

```yaml
auth:
  oauth2:
    grantType: "client_credentials"
    tokenUrl: "https://auth.example.com/token"
    clientId: "client-id"
    clientSecret:
      secret: "oauth.client.secret"
    scopes:
      - "read"
      - "write"
```

### API Key Authentication

```yaml
auth:
  apiKey:
    headerName: "X-API-Key"
    key:
      secret: "api.key"
```

### Client Certificate Authentication

```yaml
auth:
  certificate:
    keystore:
      path:
        secret: "client.keystore.path"
      password:
        secret: "client.keystore.password"
      alias: "client-cert"
```

## Connection Security

### TLS/SSL Configuration

```yaml
use:
  connections:
    - name: "secureService"
      type: "http"
      endpoint: "https://secure.example.com"
      tls:
        enabled: true
        protocols:
          - "TLSv1.2"
          - "TLSv1.3"
        cipherSuites:
          - "TLS_AES_128_GCM_SHA256"
          - "TLS_AES_256_GCM_SHA384"
        verifyHostname: true
        truststore:
          path:
            secret: "tls.truststore.path"
          password:
            secret: "tls.truststore.password"
          type: "JKS"
        keystore:
          path:
            secret: "tls.keystore.path"
          password:
            secret: "tls.keystore.password"
          type: "PKCS12"
```

Properties for TLS configuration:

| Property | Type | Description | Default |
|----------|------|-------------|---------|
| `enabled` | Boolean | Enable TLS | true for https |
| `protocols` | Array | Enabled TLS protocols | ["TLSv1.2", "TLSv1.3"] |
| `cipherSuites` | Array | Enabled cipher suites | System default |
| `verifyHostname` | Boolean | Verify hostname in cert | true |
| `truststore.path` | Secret | Path to truststore | System default |
| `truststore.password` | Secret | Truststore password | None |
| `truststore.type` | String | Truststore type | "JKS" |
| `keystore.path` | Secret | Path to keystore | None |
| `keystore.password` | Secret | Keystore password | None |
| `keystore.type` | String | Keystore type | "PKCS12" |

## Using Connections

### In HTTP Tasks

```yaml
- fetchUserData:
    callHTTP:
      connection: "apiService"  # Reference to the connection
      path: "/users/123"        # Appended to the connection endpoint
      method: "GET"
```

### In gRPC Tasks

```yaml
- getUserProfile:
    callGRPC:
      connection: "userGrpcService"
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
```

### In AsyncAPI Tasks

```yaml
- publishOrder:
    callAsyncAPI:
      api: "order-service"
      connection: "orderStream"  # Reference to the connection
      publish:
        channel: "orders"
        message:
          payload: "${ .order }"
```

### In Custom Tasks

```yaml
- customOperation:
    extension:
      database:
        connection: "orderDb"
        query: "SELECT * FROM orders WHERE customer_id = :customerId"
        parameters:
          customerId: "CUST-123"
```

## Connection Pooling

Connection pooling is automatically managed for supported connection types:

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      pooling: true  # Enable connection pooling (default)
      maxConnections: 20
      idleTimeout: PT30S
      validateOnBorrow: true
      evictionInterval: PT60S
```

## Dynamic Endpoints

Connections can use dynamic endpoints:

```yaml
use:
  connections:
    - name: "dynamicService"
      type: "http"
      endpoint: "https://${ .environment }.api.example.com"
```

## Connection Templates

Define connection templates that can be reused:

```yaml
use:
  connectionTemplates:
    - name: "apiServiceTemplate"
      type: "http"
      headers:
        User-Agent: "Lemline/1.0"
        Accept: "application/json"
      timeout: PT30S
      tls:
        protocols:
          - "TLSv1.2"
          - "TLSv1.3"
  
  connections:
    - name: "userService"
      template: "apiServiceTemplate"
      endpoint: "https://users.example.com"
    
    - name: "orderService"
      template: "apiServiceTemplate"
      endpoint: "https://orders.example.com"
```

## Connection Categories

Organize connections into categories:

```yaml
use:
  connections:
    - name: "userService"
      category: "core-services"
      type: "http"
      endpoint: "https://users.example.com"
    
    - name: "orderService"
      category: "core-services"
      type: "http"
      endpoint: "https://orders.example.com"
    
    - name: "analyticsService"
      category: "analytics"
      type: "http"
      endpoint: "https://analytics.example.com"
```

## Connection Health Checks

Configure health checks for connections:

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      healthCheck:
        enabled: true
        path: "/health"
        interval: PT30S
        timeout: PT5S
        healthyThreshold: 2
        unhealthyThreshold: 3
```

## Connection Failover

Configure failover for connections:

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      failover:
        enabled: true
        endpoints:
          - "https://api-backup1.example.com"
          - "https://api-backup2.example.com"
        retryAttempts: 3
```

## Connection Circuit Breaker

Configure circuit breakers for connections:

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      circuitBreaker:
        enabled: true
        failureThreshold: 0.5
        requestVolumeThreshold: 20
        delay: PT1M
        successThreshold: 3
```

## Connection Lifecycle Hooks

Configure lifecycle hooks for connections:

```yaml
use:
  connections:
    - name: "apiService"
      type: "http"
      endpoint: "https://api.example.com"
      lifecycle:
        onCreate:
          set:
            connections.apiService.created: true
        onClose:
          set:
            connections.apiService.closed: true
```

## Connection Metrics

Lemline collects metrics for all connections:

- Connection creation/close counts
- Active connection counts
- Connection acquisition times
- Connection usage counts
- Connection errors

Access these metrics via the metrics endpoint.

## Connection Management API

The connection management API allows programmatic control of connections:

```yaml
- manageConnection:
    extension:
      connectionManager:
        operation: "close"  # Operations: create, close, reset, status
        name: "apiService"
```

## Connection Configuration Properties

Global connection configuration:

```properties
# General connection settings
lemline.connections.default-timeout=PT30S
lemline.connections.validation-enabled=true
lemline.connections.pooling-enabled=true
lemline.connections.metrics-enabled=true

# HTTP connection settings
lemline.connections.http.default-connect-timeout=PT5S
lemline.connections.http.default-read-timeout=PT30S
lemline.connections.http.default-max-connections=100

# Database connection settings
lemline.connections.database.default-pool-size=10
lemline.connections.database.default-idle-timeout=PT10M

# Messaging connection settings
lemline.connections.kafka.default-client-id=lemline
lemline.connections.rabbitmq.default-virtual-host=/
```

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [gRPC Protocol Reference](lemline-ref-grpc.md)
- [Authentication Reference](lemline-ref-auth.md)
- [TLS Configuration](lemline-howto-tls.md)
- [Message Broker Configuration](lemline-howto-brokers.md)