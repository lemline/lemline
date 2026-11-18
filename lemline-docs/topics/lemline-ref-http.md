# HTTP Protocol Reference

This reference describes how Lemline executes `call: http` tasks: which arguments are supported, how requests are built, and what the task returns.

## HTTP Support Overview

| Feature | Support |
|---------|---------|
| Methods | Any HTTP method (`get`, `post`, `put`, `patch`, `delete`, ...) |
| URL | Plain URL or runtime expression |
| Headers and query parameters | Map of values or a runtime expression returning an object |
| Request body | JSON, sent for `post`, `put` and `patch` |
| Authentication | `basic` and `bearer` (inline or via `use.authentications`) |
| Output | `content` (default), `response` or `raw` |
| Redirects | `redirect: true` follows redirects |

Not supported: URI template placeholders, connection definitions, form or multipart bodies, OAuth 2.0 and other authentication schemes, per-call timeouts, proxies, cookies, HTTP-level circuit breakers, and runner settings to tune the HTTP client.

## Basic HTTP Request

```yaml
do:
  - fetchUserData:
      call: http
      with:
        method: get
        endpoint: https://api.example.com/users/123
```

## URL Construction

### Plain URL

```yaml
endpoint: https://api.example.com/users/123
```

### Runtime Expression

```yaml
endpoint: ${ "https://api.example.com/users/" + .user.id }
```

The expression must return a string. RFC 6570 placeholders such as `{userId}` are not expanded: build dynamic URLs with an expression.

## Request Headers

```yaml
with:
  method: get
  endpoint: https://api.example.com/data
  headers:
    Accept: application/json
    X-Request-Id: ${ .requestId }
```

`headers` can also be a single runtime expression that returns an object. Every value is sent as a string.

## Query Parameters

```yaml
with:
  method: get
  endpoint: https://api.example.com/users
  query:
    page: 1
    status: ${ .status }
```

Query parameters are appended to the URL. Like `headers`, `query` can be a runtime expression returning an object.

## Request Body

```yaml
- createUser:
    call: http
    with:
      method: post
      endpoint: https://api.example.com/users
      body:
        name: ${ .name }
        email: ${ .email }
```

The body is serialized as JSON and sent with `Content-Type: application/json`. It is only sent for `post`, `put` and `patch`.

## Response Handling

### Output Formats

| `output` | Task result |
|----------|-------------|
| `content` (default) | The response body: parsed JSON when the response content type contains `json`, a string when it contains `text`, otherwise the body encoded in Base64 |
| `response` | An object with `request` (`method`, `uri`, `headers`), `statusCode`, `headers` and `content` |
| `raw` | The response body encoded in Base64 |

```yaml
- fetchUsers:
    call: http
    with:
      method: get
      endpoint: https://api.example.com/users
      output: response
    output:
      as: ${ .content.items }
```

### Error Status Handling

A response whose status is not 2xx makes the task fail with a `runtime` error whose title contains the status code and the response body (for example `HTTP 404: ...`). Network failures fail the task the same way. Catch them with `try`:

```yaml
- fetchData:
    try:
      - callApi:
          call: http
          with:
            method: get
            endpoint: https://api.example.com/data
    catch:
      errors:
        with:
          type: https://open-workflow-specification.org/spec/1.0.0/errors/runtime
      as: apiError
      do:
        - fallback:
            set:
              data: []
```

## Authentication

### Basic Authentication

```yaml
endpoint:
  uri: https://api.example.com/data
  authentication:
    basic:
      username: ${ $secrets.API_USER }
      password: ${ $secrets.API_PASSWORD }
```

### Bearer Token

```yaml
endpoint:
  uri: https://api.example.com/data
  authentication:
    bearer:
      token: ${ $secrets.API_TOKEN }
```

### Named Authentication

```yaml
use:
  authentications:
    apiAuth:
      bearer:
        token: ${ $secrets.API_TOKEN }
do:
  - callApi:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/data
          authentication:
            use: apiAuth
```

An API key can be sent as a regular header. OAuth 2.0 is not supported yet: a task using it fails with a `configuration` error.

## Redirects

```yaml
with:
  method: get
  endpoint: https://api.example.com/old
  redirect: true
```

Without `redirect: true`, a redirect response (3xx) is not followed and fails the task like any other non-2xx status.

## Timeouts

The runner's HTTP client uses fixed limits: 15 seconds to connect, 30 seconds for the whole request and 30 seconds of socket inactivity. They cannot be changed, and a task-level `timeout` is not applied to HTTP calls.

## Retry Behavior

HTTP calls are not retried automatically. Use `try` with `retry`:

```yaml
- fetchData:
    try:
      - callApi:
          call: http
          with:
            method: get
            endpoint: https://api.example.com/data
    catch:
      errors:
        with:
          type: https://open-workflow-specification.org/spec/1.0.0/errors/runtime
      retry:
        delay:
          seconds: 2
        backoff:
          exponential: {}
        limit:
          attempt:
            count: 5
```

## HTTP Request Examples

### JSON POST Request

```yaml
- createOrder:
    call: http
    with:
      method: post
      endpoint: https://api.example.com/orders
      headers:
        Idempotency-Key: ${ .orderId }
      body:
        orderId: ${ .orderId }
        items: ${ .items }
      output: response
```

### GraphQL Query

```yaml
- queryGraphQL:
    call: http
    with:
      method: post
      endpoint: https://api.example.com/graphql
      body:
        query: "query($id: ID!) { user(id: $id) { name email } }"
        variables:
          id: ${ .userId }
```

## Related Resources

- [Call HTTP task](dsl-call-http.md)
- [Error Handling](dsl-error-handling.md)
- [Understanding Security in Lemline](lemline-explain-security.md)
