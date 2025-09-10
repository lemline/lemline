# HTTP Protocol Reference

This reference documents all aspects of HTTP support in Lemline, including configuration, features, and advanced usage.

## HTTP Support Overview

Lemline provides comprehensive HTTP client capabilities for workflow tasks, enabling:

1. **REST API Integration**: Call RESTful services using standard HTTP methods
2. **JSON/XML Processing**: Handle various content types
3. **Authentication**: Support for multiple authentication mechanisms
4. **Request Customization**: Headers, query parameters, content types
5. **Response Handling**: Status code handling, response transformation
6. **Retry & Circuit Breaking**: Resilient HTTP communication

## Basic HTTP Request

The `callHTTP` task provides HTTP client functionality:

```yaml
- fetchUserData:
    callHTTP:
      url: "https://api.example.com/users/123"
      method: "GET"
```

## HTTP Methods

Lemline supports all standard HTTP methods:

| Method | Description | Request Body Support | Idempotent |
|--------|-------------|----------------------|------------|
| GET | Retrieve resource | No | Yes |
| POST | Create resource | Yes | No |
| PUT | Update/replace resource | Yes | Yes |
| DELETE | Remove resource | Optional | Yes |
| PATCH | Partial update | Yes | No |
| HEAD | Headers only (no body) | No | Yes |
| OPTIONS | Get supported methods | No | Yes |
| TRACE | Diagnostic trace | No | Yes |

Example with different methods:

```yaml
# GET request
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"

# POST request with body
- createUser:
    callHTTP:
      url: "https://api.example.com/users"
      method: "POST"
      body: "${ .user }"
      headers:
        Content-Type: "application/json"

# PUT request
- updateUser:
    callHTTP:
      url: "https://api.example.com/users/123"
      method: "PUT"
      body: "${ .user }"

# DELETE request
- deleteUser:
    callHTTP:
      url: "https://api.example.com/users/123"
      method: "DELETE"
```

## URL Construction

### Base URL

The base URL can be specified directly:

```yaml
url: "https://api.example.com/data"
```

### Dynamic URLs

URLs can be constructed dynamically using expressions:

```yaml
url: "https://api.example.com/users/${ .userId }"
```

### URL Templates

For more complex URLs, URI templates can be used:

```yaml
url:
  template: "https://api.example.com/users/{userId}/orders/{orderId}"
  params:
    userId: "${ .user.id }"
    orderId: "${ .order.id }"
```

This is equivalent to: `https://api.example.com/users/123/orders/456` (assuming user.id=123 and order.id=456)

### Connection Configuration

For repeated calls to the same service, define a connection:

```yaml
use:
  connections:
    - name: "userApi"
      endpoint: "https://api.example.com"
      tls:
        verifyHostname: true
      timeout: PT30S

# Using the connection
- fetchUser:
    callHTTP:
      connection: "userApi"
      path: "/users/123"
      method: "GET"
```

## Request Headers

### Static Headers

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      headers:
        Accept: "application/json"
        User-Agent: "Lemline/1.0"
```

### Dynamic Headers

```yaml
headers:
  Authorization: "Bearer ${ .accessToken }"
  X-Request-ID: "${ uuid() }"
```

### Default Headers

Lemline sets these default headers:
- `User-Agent: Lemline/{version}`
- `Accept: application/json`

## Query Parameters

### Static Query Parameters

```yaml
- searchUsers:
    callHTTP:
      url: "https://api.example.com/users"
      method: "GET"
      query:
        role: "admin"
        status: "active"
```

This produces: `https://api.example.com/users?role=admin&status=active`

### Dynamic Query Parameters

```yaml
query:
  q: "${ .searchTerm }"
  limit: "${ .pageSize }"
  offset: "${ .pageSize * (.pageNumber - 1) }"
```

### Array Query Parameters

```yaml
query:
  id: ["123", "456", "789"]
```

This produces: `?id=123&id=456&id=789`

With format control:

```yaml
query:
  id:
    values: ["123", "456", "789"]
    format: "comma"  # Options: "repeat" (default), "comma", "pipe", "space", "multi"
```

Format `comma` produces: `?id=123,456,789`

## Request Body

### JSON Body

```yaml
- createUser:
    callHTTP:
      url: "https://api.example.com/users"
      method: "POST"
      body: "${ .user }"
      headers:
        Content-Type: "application/json"
```

### String Body

```yaml
body: "This is a plain text body"
headers:
  Content-Type: "text/plain"
```

### Form Data

```yaml
body:
  format: "form"
  data:
    name: "John Doe"
    email: "john@example.com"
    subscribe: true
```

### Multipart Form Data

```yaml
body:
  format: "multipart"
  parts:
    - name: "metadata"
      content: "${ .metadata }"
      headers:
        Content-Type: "application/json"
    - name: "file"
      content: "${ .fileContent }"
      filename: "document.pdf"
      headers:
        Content-Type: "application/pdf"
```

### Binary Data

```yaml
body: "${ .binaryData }"
headers:
  Content-Type: "application/octet-stream"
```

## Response Handling

### Response Structure

HTTP responses provide these properties:

```
status: HTTP status code (number)
headers: Response headers (object)
body: Response body (depends on Content-Type)
```

### Basic Response Handling

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
    # Results automatically stored in task output
```

### Response Transformation

Using the `output` property to extract specific parts:

```yaml
- fetchUsers:
    callHTTP:
      url: "https://api.example.com/users"
      method: "GET"
      output:
        from: ".body.items"  # Extract items array from response body
        as: "userList"       # Store as userList variable
```

### Error Status Handling

Non-2xx status codes raise a `communication` error by default:

```yaml
- fetchData:
    try:
      do:
        - callApi:
            callHTTP:
              url: "https://api.example.com/data"
              method: "GET"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
            as: "apiError"
          do:
            - handleError:
                switch:
                  - condition: "${ .apiError.status == 404 }"
                    do:
                      # Handle 404 Not Found
                  - condition: "${ .apiError.status == 401 }"
                    do:
                      # Handle 401 Unauthorized
```

### Disabling Error Status Handling

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  failOnErrorStatus: false  # Don't raise error for non-2xx status
```

## Content Type Handling

### Request Content Type

The `Content-Type` header specifies request format:

```yaml
headers:
  Content-Type: "application/json"  # Send JSON
```

Supported content types include:
- `application/json`
- `application/xml`
- `text/plain`
- `application/x-www-form-urlencoded`
- `multipart/form-data`
- `application/octet-stream`

### Response Content Type

Response parsing depends on the `Content-Type` header in the response:

| Content-Type | Parsing Behavior |
|--------------|------------------|
| `application/json` | Parsed as JSON object |
| `application/xml` | Parsed as XML (accessible via expressions) |
| `text/plain` | Returned as string |
| `application/octet-stream` | Returned as string (base64) |
| Other | Returned as string |

### Custom Response Parsing

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  responseType: "json"  # Force JSON parsing regardless of Content-Type
```

## Authentication

### Basic Authentication

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      auth:
        basic:
          username: "user"
          password:
            secret: "api.password"
```

### Bearer Token

```yaml
auth:
  bearer:
    token:
      secret: "api.token"
```

### API Key

```yaml
auth:
  apiKey:
    headerName: "X-API-Key"
    key:
      secret: "api.key"
```

### OAuth 2.0

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

### Custom Authentication

```yaml
auth:
  custom:
    headers:
      X-Auth-Token:
        secret: "custom.token"
      X-Timestamp: "${ now() }"
```

## Redirects

### Following Redirects

Lemline follows redirects by default:

```yaml
callHTTP:
  url: "https://api.example.com/resource"
  method: "GET"
  followRedirects: true  # Default behavior
```

### Disabling Redirects

```yaml
callHTTP:
  url: "https://api.example.com/resource"
  method: "GET"
  followRedirects: false  # Don't follow redirects
```

### Redirect Limits

```yaml
callHTTP:
  url: "https://api.example.com/resource"
  method: "GET"
  maxRedirects: 5  # Follow up to 5 redirects
```

## Timeouts

### Request Timeout

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  timeout: PT30S  # 30 second timeout
```

### Connection Timeout

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  connectTimeout: PT5S  # 5 second connection timeout
  readTimeout: PT30S   # 30 second read timeout
```

## Retry Behavior

HTTP requests can automatically retry on failure using the `try` and `retry` tasks:

```yaml
- fetchData:
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
        - callApi:
            callHTTP:
              url: "https://api.example.com/data"
              method: "GET"
```

## Circuit Breaker

Circuit breakers prevent cascading failures:

```yaml
- fetchUserData:
    extension:
      circuitBreaker:
        failureRatio: 0.5
        requestVolumeThreshold: 20
        delay: PT1M
    callHTTP:
      url: "https://users.example.com/api/user/${ .userId }"
```

## HTTP Client Configuration

Global HTTP client configuration:

```properties
# Connection pooling
lemline.http.max-connections=100
lemline.http.max-connections-per-route=20
lemline.http.keep-alive-time=PT5M

# Default timeouts
lemline.http.connect-timeout=PT5S
lemline.http.read-timeout=PT30S
lemline.http.call-timeout=PT60S

# Miscellaneous settings
lemline.http.follow-redirects=true
lemline.http.max-redirects=5
lemline.http.compression-enabled=true
lemline.http.expect-continue=true
```

## Advanced Features

### HTTP/2 Support

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  http2Enabled: true  # Use HTTP/2 if supported by server
```

### Compression

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  compressionEnabled: true  # Enable request/response compression
```

### Cookies

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  cookiesEnabled: true  # Enable cookie store
```

### Proxy Configuration

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  proxy:
    host: "proxy.example.com"
    port: 8080
    username: "proxyuser"
    password:
      secret: "proxy.password"
```

### Connection Pooling

```yaml
callHTTP:
  url: "https://api.example.com/data"
  method: "GET"
  keepAlive: true  # Use connection pooling
```

### Metrics and Tracing

HTTP calls automatically generate metrics and tracing information:

```properties
# Enable detailed HTTP tracing
lemline.http.trace-enabled=true
lemline.http.trace-level=BODY  # Options: BASIC, HEADERS, BODY
```

### WebSocket Support

```yaml
- connectWebSocket:
    extension:
      webSocket:
        url: "wss://api.example.com/ws"
        subprotocols: ["mqtt", "wamp"]
        headers:
          Authorization: "Bearer ${ .token }"
        messageHandler:
          set:
            lastMessage: "${ $message }"
```

## HTTP Request Examples

### JSON POST Request

```yaml
- createUser:
    callHTTP:
      url: "https://api.example.com/users"
      method: "POST"
      headers:
        Content-Type: "application/json"
        Accept: "application/json"
      body:
        name: "John Doe"
        email: "john@example.com"
        role: "user"
```

### File Upload

```yaml
- uploadDocument:
    callHTTP:
      url: "https://api.example.com/documents"
      method: "POST"
      body:
        format: "multipart"
        parts:
          - name: "metadata"
            content:
              title: "Annual Report"
              tags: ["finance", "2023"]
            headers:
              Content-Type: "application/json"
          - name: "file"
            content: "${ .fileContent }"
            filename: "report.pdf"
            headers:
              Content-Type: "application/pdf"
```

### GraphQL Query

```yaml
- executeGraphQL:
    callHTTP:
      url: "https://api.example.com/graphql"
      method: "POST"
      headers:
        Content-Type: "application/json"
      body:
        query: """
          query GetUser($id: ID!) {
            user(id: $id) {
              id
              name
              email
            }
          }
        """
        variables:
          id: "${ .userId }"
```

## Related Resources

- [Making HTTP Requests](lemline-howto-http.md)
- [OpenAPI Integration](lemline-howto-openapi.md)
- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [API Key Authentication](lemline-howto-api-keys.md)
- [TLS Configuration](lemline-howto-tls.md)