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

<code-block lang="yaml" src="workflows/lemline-ref-http/basic-http-request.yaml" include-lines="6-11"/>

## URL Construction

### Plain URL

<code-block lang="yaml" src="workflows/reference/http-literal-endpoint.yaml" include-lines="11-11"/>

### Runtime Expression

<code-block lang="yaml" src="workflows/reference/http-expression-endpoint.yaml" include-lines="11-11"/>

The expression must return a string. RFC 6570 placeholders such as `{userId}` are not expanded: build dynamic URLs with an expression.

## Request Headers

<code-block lang="yaml" src="workflows/reference/http-request-headers.yaml" include-lines="9-14"/>

`headers` can also be a single runtime expression that returns an object. Every value is sent as a string.

## Query Parameters

<code-block lang="yaml" src="workflows/reference/http-query-parameters.yaml" include-lines="9-14"/>

Query parameters are appended to the URL. Like `headers`, `query` can be a runtime expression returning an object.

## Request Body

<code-block lang="yaml" src="workflows/lemline-ref-http/request-body.yaml" include-lines="7-14"/>

The body is serialized as JSON and sent with `Content-Type: application/json`. It is only sent for `post`, `put` and `patch`.

## Response Handling

### Output Formats

| `output` | Task result |
|----------|-------------|
| `content` (default) | The response body: parsed JSON when the response content type contains `json`, a string when it contains `text`, otherwise the body encoded in Base64 |
| `response` | An object with `request` (`method`, `uri`, `headers`), `statusCode`, `headers` and `content` |
| `raw` | The response body encoded in Base64 |

<code-block lang="yaml" src="workflows/lemline-ref-http/output-formats.yaml" include-lines="7-14"/>

### Error Status Handling

A response whose status is not 2xx makes the task fail with a `communication` error. Its `status` is the HTTP status and its title contains the status code and the response body (for example `HTTP 404: ...`). A call that gets no response (unknown host, refused connection, timeout) also fails with a `communication` error, with status 500 and a title naming the server (for example `Unable to reach https://api.example.com: unresolved host`). Catch them with `try`, and add `status` to the filter to handle one status only:

<code-block lang="yaml" src="workflows/lemline-ref-http/error-status-handling.yaml" include-lines="7-22"/>

## Authentication

Authentication fields evaluate runtime expressions, so they can read secrets. A workflow only sees the secrets it declares in `use.secrets`; an undeclared secret evaluates to `null` and the task faults.

### Basic Authentication

<code-block lang="yaml" src="workflows/reference/http-inline-basic-authentication.yaml" include-lines="15-20"/>

### Bearer Token

<code-block lang="yaml" src="workflows/reference/http-inline-bearer-token.yaml" include-lines="14-18"/>

### Named Authentication

<code-block lang="yaml" src="workflows/lemline-ref-http/named-authentication.yaml" include-lines="6-21"/>

An API key can be sent as a regular header. OAuth 2.0 is not supported yet: a task using it fails with a `configuration` error.

## Redirects

<code-block lang="yaml" src="workflows/reference/http-follow-redirects.yaml" include-lines="9-12"/>

Without `redirect: true`, a redirect response (3xx) is not followed and fails the task like any other non-2xx status.

## Timeouts

The runner's HTTP client uses fixed limits: 15 seconds to connect, 30 seconds for the whole request and 30 seconds of socket inactivity. They cannot be changed, and a task-level `timeout` is not applied to HTTP calls.

## Retry Behavior

HTTP calls are not retried automatically. Use `try` with `retry`:

<code-block lang="yaml" src="workflows/lemline-ref-http/retry-behavior.yaml" include-lines="7-25"/>

## HTTP Request Examples

### JSON POST Request

<code-block lang="yaml" src="workflows/lemline-ref-http/json-post-request.yaml" include-lines="7-17"/>

### GraphQL Query

<code-block lang="yaml" src="workflows/lemline-ref-http/graphql-query.yaml" include-lines="7-15"/>

## Related Resources

- [Call HTTP task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#http-call)
- [Error Handling](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#fault-tolerance)
- [Understanding Security in Lemline](lemline-explain-security.md)
