---
title: How to make an HTTP call
---

# How to call an HTTP service

Set `call: http` and configure the HTTP `method` and `endpoint` under `with`.

<code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

## Build the request

Use `headers` and `query` for request headers and query parameters. For a POST or
PUT request, supply `body` as a value or a workflow expression. See the
[HTTP Task Reference](lemline-ref-http.md) for request options and output modes.

## Use a dynamic endpoint

Use a jq expression for the complete endpoint when it depends on task input.
Escape an input value before inserting it into the URL path.

<code-block lang="yaml" src="workflows/reference/dynamic-http-endpoint.yaml"/>

## Authenticate

Use a service-specific header or query parameter for an [API key](lemline-howto-api-keys.md).
Use an endpoint authentication policy for [basic or bearer authentication](lemline-ref-auth.md).

## Recover from an HTTP error

Wrap the call in a `try` task. Match the status in `catch.errors.with` and add a
retry policy or a fallback list where appropriate.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>
