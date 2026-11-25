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
| Redirects | Always followed on the current build, whatever `redirect` says ([issue 112](https://github.com/lemline/lemline/issues/112)) |

Not supported: URI template placeholders, connection definitions, form or multipart bodies, OAuth 2.0 and other authentication schemes, per-call timeouts, proxies, cookies, HTTP-level circuit breakers, and runner settings to tune the HTTP client.

## Basic HTTP Request

<code-block lang="yaml" src="workflows/lemline-ref-http/basic-http-request.yaml"/>

## URL Construction

### Plain URL

<code-block lang="yaml" src="workflows/reference/http-literal-endpoint.yaml"/>

### Runtime Expression

<code-block lang="yaml" src="workflows/reference/http-expression-endpoint.yaml"/>

The expression must return a string. RFC 6570 placeholders such as `{userId}` are not expanded: build dynamic URLs with an expression.

## Request Headers

<code-block lang="yaml" src="workflows/reference/http-request-headers.yaml"/>

`headers` can also be a single runtime expression that returns an object. Every value is sent as a string.

## Query Parameters

<code-block lang="yaml" src="workflows/reference/http-query-parameters.yaml"/>

Query parameters are appended to the URL. Like `headers`, `query` can be a runtime expression returning an object.

## Request Body

<code-block lang="yaml" src="workflows/lemline-ref-http/request-body.yaml"/>

The body is serialized as JSON and sent with `Content-Type: application/json`. It is only sent for `post`, `put` and `patch`.

## Response Handling

### Output Formats

| `output` | Task result |
|----------|-------------|
| `content` (default) | The response body: parsed JSON when the response content type contains `json`, a string when it contains `text`, otherwise the body encoded in Base64 |
| `response` | An object with `request` (`method`, `uri`, `headers`), `statusCode`, `headers` and `content` |
| `raw` | The response body encoded in Base64 |

<code-block lang="yaml" src="workflows/lemline-ref-http/output-formats.yaml"/>

### Error Status Handling

A response whose status is not 2xx makes the task fail with a `communication` error. Its `status` is the HTTP status and its title contains the status code and the response body (for example `HTTP 404: ...`). A call that gets no response (unknown host, refused connection, timeout) also fails with a `communication` error, with status 500 and a title naming the server (for example `Unable to reach https://api.example.com: unresolved host`). Catch them with `try`, and add `status` to the filter to handle one status only:

<code-block lang="yaml" src="workflows/lemline-ref-http/error-status-handling.yaml"/>

## Authentication

Authentication fields evaluate runtime expressions, so they can read secrets. A workflow only sees the secrets it declares in `use.secrets`; an undeclared secret evaluates to `null` and the task faults.

### Basic Authentication

<code-block lang="yaml" src="workflows/reference/http-inline-basic-authentication.yaml"/>

### Bearer Token

<code-block lang="yaml" src="workflows/reference/http-inline-bearer-token.yaml"/>

### Named Authentication

<code-block lang="yaml" src="workflows/lemline-ref-http/named-authentication.yaml"/>

An API key can be sent as a regular header. OAuth 2.0 is not supported yet: a task using it fails with a `runtime` error titled `Unsupported OAuth2 authentication`.

## Redirects

<code-block lang="yaml" src="workflows/reference/http-follow-redirects.yaml"/>

Without `redirect: true`, a redirect response (3xx) should not be followed and should fail the task like any other non-2xx status. On the current build, redirects are always followed, with or without `redirect: true` ([issue 112](https://github.com/lemline/lemline/issues/112)): the example below returns the stock.

<code-block lang="yaml" src="workflows/reference/http-no-redirect.yaml"/>

## Timeouts

The runner's HTTP client uses fixed limits: 15 seconds to connect, 30 seconds for the whole request and 30 seconds of socket inactivity. They cannot be changed, and a task-level `timeout` is not applied to HTTP calls.

## Retry Behavior

HTTP calls are not retried automatically. Use `try` with `retry`:

<code-block lang="yaml" src="workflows/lemline-ref-http/retry-behavior.yaml"/>

## HTTP Request Examples

### JSON POST Request

<code-block lang="yaml" src="workflows/lemline-ref-http/json-post-request.yaml"/>

### GraphQL Query

<code-block lang="yaml" src="workflows/lemline-ref-http/graphql-query.yaml"/>

## Run the Examples

Every example on this page is a complete workflow that calls the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop). Complete
[the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and start the shop
from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The authentication examples read the shop's demo credentials from secrets. Stop the setup worker with Ctrl+C, export
them, and start the worker again (with the JAR, substitute `java -jar lemline.jar` for `bin/lemline`):

```bash
export API_USER='demo'
export API_PASSWORD='demo-password'
export API_TOKEN='demo-token'
bin/lemline listen --debug
```

From a second terminal in the setup directory, register and start each example:

```bash
bin/lemline definition post -f basic-http-request.yaml
bin/lemline instance start examples basic-http-request
bin/lemline definition post -f http-literal-endpoint.yaml
bin/lemline instance start examples http-literal-endpoint
bin/lemline definition post -f http-expression-endpoint.yaml
bin/lemline instance start examples http-expression-endpoint --input '{"customer":{"id":"C-200"}}'
bin/lemline definition post -f http-request-headers.yaml
bin/lemline instance start examples http-request-headers --input '{"requestId":"REQ-42"}'
bin/lemline definition post -f http-query-parameters.yaml
bin/lemline instance start examples http-query-parameters --input '{"category":"home"}'
bin/lemline definition post -f request-body.yaml
bin/lemline instance start examples request-body --input '{"name":"Hedy Lamarr","email":"hedy@example.com"}'
bin/lemline definition post -f output-formats.yaml
bin/lemline instance start examples output-formats
bin/lemline definition post -f error-status-handling.yaml
bin/lemline instance start examples error-status-handling --input '{"productId":"SKU-404"}'
bin/lemline definition post -f http-inline-basic-authentication.yaml
bin/lemline instance start examples http-inline-basic-authentication
bin/lemline definition post -f http-inline-bearer-token.yaml
bin/lemline instance start examples http-inline-bearer-token
bin/lemline definition post -f named-authentication.yaml
bin/lemline instance start examples named-authentication
bin/lemline definition post -f http-follow-redirects.yaml
bin/lemline instance start examples http-follow-redirects
bin/lemline definition post -f http-no-redirect.yaml
bin/lemline instance start examples http-no-redirect
bin/lemline definition post -f retry-behavior.yaml
bin/lemline instance start examples retry-behavior --input '{"failures":2}'
bin/lemline definition post -f json-post-request.yaml
bin/lemline instance start examples json-post-request --input '{"orderId":"ORD-1601","items":[{"id":"SKU-1","quantity":1}]}'
bin/lemline definition post -f graphql-query.yaml
bin/lemline instance start examples customer-graphql-lookup --input '{"customerId":"C-100"}'
```

| Example | Expected result |
|---|---|
| `basic-http-request`, `http-literal-endpoint` | Customer C-100, Ada Lovelace |
| `http-expression-endpoint` | Customer C-200, Alan Turing |
| `http-request-headers` | The stock; the shop's request list shows `x-request-id: REQ-42` |
| `http-query-parameters` | The home products, SKU-4 and SKU-5 |
| `request-body` | The new customer `C-901` |
| `output-formats` | `{"status":200,"ids":["SKU-1","SKU-2","SKU-3"]}` |
| `error-status-handling` | `{"data":[],"reason":"HTTP 404: …"}` |
| `http-inline-basic-authentication` | The partner stock with `"authenticatedWith":"basic"` |
| `http-inline-bearer-token`, `named-authentication` | The partner stock with `"authenticatedWith":"bearer"` |
| `http-follow-redirects` | The stock, read from `/stock` after the 301 |
| `http-no-redirect` | The stock too: the redirect is followed anyway (issue 112) |
| `retry-behavior` | The stock after two retries |
| `json-post-request` | The full response, `statusCode` 200 with the reservation in `content` |
| `customer-graphql-lookup` | `{"data":{"customer":{…"name":"Ada Lovelace"…}}}` |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Related Resources

- [Call HTTP task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#http-call)
- [Error Handling](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#fault-tolerance)
- [Understanding Security in Lemline](lemline-explain-security.md)
