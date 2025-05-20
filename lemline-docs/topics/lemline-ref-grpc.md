# gRPC Protocol Reference

This reference documents all aspects of gRPC support in Lemline, including configuration, features, and advanced usage.

## gRPC Support Overview

Lemline provides comprehensive gRPC client capabilities, enabling workflows to:

1. **Call gRPC Services**: Make calls to gRPC services using defined service contracts
2. **Work with Protocol Buffers**: Interact with Protocol Buffer (protobuf) messages
3. **Support Multiple Patterns**: Unary, server streaming, client streaming, and bidirectional streaming
4. **Handle Authentication**: Multiple authentication mechanisms
5. **Support Resilience Patterns**: Timeouts, retries, and circuit breakers

## Basic gRPC Request

The `callGRPC` task provides gRPC client functionality:

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
```

## gRPC Service Definition

### Service References

Services can be referenced using fully qualified names:

```yaml
service: "com.example.users.UserService"
```

### Protobuf Support

Lemline automatically loads and parses .proto files from:

1. Pre-built descriptors in the classpath
2. Proto files in the `/proto` directory
3. Referenced proto files with URLs or file paths

### Custom Descriptor Path

Specify a custom descriptor path:

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  descriptorPath: "/path/to/user_service.desc"
  message:
    userId: "123"
```

### Dynamic Service Discovery

Use service discovery to find gRPC services:

```yaml
callGRPC:
  serviceDiscovery:
    type: "dns"
    service: "user-service.example.com"
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
```

## gRPC Request Types

Lemline supports all four gRPC interaction patterns:

### Unary RPC

A simple request-response pattern (most common):

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
```

### Server Streaming RPC

Server returns a stream of messages:

```yaml
- watchUserStatus:
    callGRPC:
      service: "users.UserService"
      rpc: "WatchUserStatus"
      message:
        userId: "123"
      streaming:
        type: "server"
        collect: true  # Collect all messages
        timeout: PT1M  # Stream for up to 1 minute
```

### Client Streaming RPC

Client sends a stream of messages:

```yaml
- uploadDocuments:
    callGRPC:
      service: "documents.DocumentService"
      rpc: "UploadDocuments"
      streaming:
        type: "client"
        messages: "${ .documents }"  # Array of documents to stream
```

### Bidirectional Streaming RPC

Both client and server stream messages:

```yaml
- chatSession:
    callGRPC:
      service: "chat.ChatService"
      rpc: "Chat"
      streaming:
        type: "bidirectional"
        messages: "${ .userMessages }"  # Messages to send
        onMessage:
          set:
            lastResponse: "${ $message }"  # Store each received message
        timeout: PT5M  # Stream for up to 5 minutes
```

## Message Formatting

### Simple Messages

```yaml
message:
  userId: "123"
  includeDetails: true
```

### Nested Messages

```yaml
message:
  user:
    id: "123"
    role: "ADMIN"
  filters:
    includeInactive: false
    fields: ["profile", "settings", "history"]
```

### Using Expressions

```yaml
message:
  userId: "${ .userId }"
  timestamp: "${ now() }"
  requestId: "${ uuid() }"
```

### Well-Known Types

Lemline handles well-known Protobuf types automatically:

```yaml
message:
  userId: "123"
  lastSeen:  # google.protobuf.Timestamp
    seconds: 1625097600
    nanos: 0
  settings:  # google.protobuf.Struct
    theme: "dark"
    notifications: true
  tags: ["user", "active"]  # repeated string
```

## Response Handling

### Response Structure

gRPC responses are converted to a JSON structure that reflects the Protobuf message.

### Basic Response Handling

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
    # Response is automatically stored in task output
```

### Response Transformation

Using the `output` property to extract specific parts:

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
      output:
        from: ".name"  # Extract name field from response
        as: "userName"  # Store as userName variable
```

### Streaming Response Handling

For streaming responses, you can collect all messages or process them individually:

```yaml
# Collect all messages in an array
callGRPC:
  service: "products.ProductService"
  rpc: "ListProducts"
  message:
    category: "electronics"
  streaming:
    type: "server"
    collect: true  # Collect all messages in an array

# Process each message as it arrives
callGRPC:
  service: "products.ProductService"
  rpc: "ListProducts"
  message:
    category: "electronics"
  streaming:
    type: "server"
    collect: false  # Default
    onMessage:
      set:
        lastProduct: "${ $message }"  # Store each message
        productCount: "${ .productCount + 1 }"  # Count messages
```

## Authentication

### TLS/SSL Authentication

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
      tls:
        enabled: true
        keystore:
          path:
            secret: "grpc.keystore.path"
          password:
            secret: "grpc.keystore.password"
        truststore:
          path:
            secret: "grpc.truststore.path"
          password:
            secret: "grpc.truststore.password"
```

### Bearer Token Authentication

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  auth:
    bearer:
      token:
        secret: "grpc.token"
```

### Basic Authentication

```yaml
auth:
  basic:
    username: "user"
    password:
      secret: "grpc.password"
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

### Custom Metadata (Headers)

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  metadata:
    x-api-key:
      secret: "api.key"
    x-request-id: "${ uuid() }"
```

## Error Handling

### Default Error Handling

gRPC errors raise a `communication` error with status details:

```yaml
- getUserProfile:
    try:
      do:
        - callUserService:
            callGRPC:
              service: "users.UserService"
              rpc: "GetUserProfile"
              message:
                userId: "123"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
            as: "grpcError"
          do:
            - handleError:
                switch:
                  - condition: "${ .grpcError.status == 5 }"  # NOT_FOUND
                    do:
                      # Handle user not found
                  - condition: "${ .grpcError.status == 7 }"  # PERMISSION_DENIED
                    do:
                      # Handle permission denied
```

### gRPC Status Codes

Lemline maps gRPC status codes to error details:

| gRPC Status Code | Description | Lemline Error Type |
|------------------|-------------|-------------------|
| 0 | OK | None (success) |
| 1 | CANCELLED | communication |
| 2 | UNKNOWN | runtime |
| 3 | INVALID_ARGUMENT | validation |
| 4 | DEADLINE_EXCEEDED | timeout |
| 5 | NOT_FOUND | communication |
| 6 | ALREADY_EXISTS | communication |
| 7 | PERMISSION_DENIED | authorization |
| 8 | RESOURCE_EXHAUSTED | communication |
| 9 | FAILED_PRECONDITION | communication |
| 10 | ABORTED | communication |
| 11 | OUT_OF_RANGE | validation |
| 12 | UNIMPLEMENTED | communication |
| 13 | INTERNAL | runtime |
| 14 | UNAVAILABLE | communication |
| 15 | DATA_LOSS | runtime |
| 16 | UNAUTHENTICATED | authentication |

## Timeouts

### Request Timeout

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  timeout: PT30S  # 30 second timeout
```

### Streaming Timeout

```yaml
callGRPC:
  service: "products.ProductService"
  rpc: "ListProducts"
  message:
    category: "electronics"
  streaming:
    type: "server"
    timeout: PT5M  # Stream for up to 5 minutes
```

## Retry Behavior

gRPC calls can automatically retry on failure using the `try` and `retry` tasks:

```yaml
- getUserProfile:
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
        - callUserService:
            callGRPC:
              service: "users.UserService"
              rpc: "GetUserProfile"
              message:
                userId: "123"
```

## Circuit Breaker

Circuit breakers prevent cascading failures:

```yaml
- getUserProfile:
    extension:
      circuitBreaker:
        failureRatio: 0.5
        requestVolumeThreshold: 20
        delay: PT1M
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
```

## gRPC Client Configuration

Global gRPC client configuration:

```properties
# Connection settings
lemline.grpc.max-inbound-message-size=4194304
lemline.grpc.max-outbound-message-size=4194304
lemline.grpc.keep-alive-time=PT60S
lemline.grpc.keep-alive-timeout=PT20S
lemline.grpc.keep-alive-without-calls=false

# Concurrency settings
lemline.grpc.max-concurrent-calls=100

# Default timeouts
lemline.grpc.deadline=PT60S

# Security settings
lemline.grpc.security.tls.enabled=true
lemline.grpc.security.tls.trust-all-certs=false
```

## Service Connection Management

### Service Registry Integration

```yaml
callGRPC:
  serviceRegistry:
    type: "consul"
    url: "http://consul.example.com:8500"
  serviceName: "user-service"
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
```

### Load Balancing

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  loadBalancing:
    policy: "round_robin"  # Options: "pick_first", "round_robin"
```

### Connection Pooling

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  connection:
    pooling: true
    maxSize: 10
```

## Advanced Features

### Compression

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  compression: "gzip"  # Options: none, gzip, snappy
```

### Wait For Ready

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  waitForReady: true  # Wait for service to be ready
```

### Deadlines vs. Timeouts

```yaml
callGRPC:
  service: "users.UserService"
  rpc: "GetUserProfile"
  message:
    userId: "123"
  deadline: "2023-12-31T23:59:59Z"  # Absolute deadline
  # OR
  timeout: PT30S  # Relative timeout
```

### Metrics and Tracing

gRPC calls automatically generate metrics and tracing information:

```properties
# Enable detailed gRPC tracing
lemline.grpc.trace-enabled=true
lemline.grpc.trace-level=PAYLOAD  # Options: BASIC, HEADERS, PAYLOAD
```

## gRPC Request Examples

### Basic Unary Call

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "123"
        includeDetails: true
```

### Server Streaming Example

```yaml
- getProductUpdates:
    callGRPC:
      service: "products.ProductService"
      rpc: "WatchProductUpdates"
      message:
        categoryId: "electronics"
        minPrice: 100
        maxPrice: 500
      streaming:
        type: "server"
        collect: true
        timeout: PT10M
        bufferSize: 1000  # Maximum number of messages to collect
```

### Client Streaming Example

```yaml
- uploadTelemetry:
    callGRPC:
      service: "telemetry.TelemetryService"
      rpc: "UploadMetrics"
      streaming:
        type: "client"
        messages: "${ .metrics }"  # Array of metric readings
```

### Bidirectional Streaming Example

```yaml
- chatSession:
    callGRPC:
      service: "chat.ChatService"
      rpc: "Chat"
      streaming:
        type: "bidirectional"
        messages: "${ .userMessages }"
        onMessage:
          set:
            responses: "${ append(.responses, $message) }"
            lastResponse: "${ $message }"
        timeout: PT30M
```

## Related Resources

- [Using gRPC Services](lemline-howto-grpc.md)
- [Authentication Reference](lemline-ref-auth.md)
- [Resilience Patterns](dsl-resilience-patterns.md)
- [HTTP Protocol Reference](lemline-ref-http.md)