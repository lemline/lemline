---
title: How to make an HTTP call
---

# How to call an HTTP service

Set `call: http` and configure the HTTP `method` and `endpoint` under `with`.

The examples call the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop). Complete
[the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Start the shop from the root of the
Lemline source checkout, then run the other commands from a second terminal in the setup directory. With the JAR,
substitute `java -jar lemline.jar` for `bin/lemline`.

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Save this definition as `check-stock.yaml`:

<code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

```bash
bin/lemline definition post -f check-stock.yaml
bin/lemline instance start examples check-stock
```

The response body becomes the task output: `{"sku":"SKU-1","available":100,"reserved":0}`.

## Build the request

Use `headers` and `query` for request headers and query parameters. For a POST or
PUT request, supply `body` as a value or a workflow expression. See the
[HTTP Task Reference](lemline-ref-http.md) for request options and output modes.

## Use a dynamic endpoint

Use a jq expression for the complete endpoint when it depends on task input.
Escape an input value before inserting it into the URL path.

<code-block lang="yaml" src="workflows/reference/dynamic-http-endpoint.yaml"/>

Save it as `dynamic-http-endpoint.yaml` and read one known and one unknown product:

```bash
bin/lemline definition post -f dynamic-http-endpoint.yaml
bin/lemline instance start examples dynamic-http-endpoint --input '{"productId":"SKU-2"}'
bin/lemline instance start examples dynamic-http-endpoint --input '{"productId":"SKU 404"}'
```

The first instance returns the watering can, `{"id":"SKU-2","name":"Watering can","category":"garden","price":12,"available":10}`.
For the second, `@uri` sends `/products/SKU%20404`; the shop answers HTTP 404 and the workflow faults with a
`runtime` error whose title starts with `HTTP 404`. Write path templates such as `{productId}` as an expression like
this one: Lemline does not expand URI templates.

## Authenticate

Use a service-specific header or query parameter for an [API key](lemline-howto-api-keys.md).
Use an endpoint authentication policy for [basic or bearer authentication](lemline-ref-auth.md).

## Recover from an HTTP error

Wrap the call in a `try` task, select the errors to handle in `catch`, and add a retry policy or a fallback list
where appropriate. On the current build, a failed HTTP call raises a `runtime` error with status 500, and the HTTP
status is only in the title, `HTTP <code>: <body>` ([issue 97](https://github.com/lemline/lemline/issues/97)).
Match it with `catch.when` on `$error.title`, as here, rather than with `catch.errors.with.status`.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

The `failures` input makes the shop answer HTTP 503 that many times. Save the definition as `retry-and-fallback.yaml`:

```bash
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":1}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
```

The first instance returns the stock after one retry. The second fails four times, then returns
`{"inventory":[],"stale":true}`. Allow about a minute: the worker picks up delayed retries every 10 seconds.
Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

Run [the checkout workflow](lemline-tutorial-order-processing.md) to see request bodies, HTTP failure handling and idempotent release against a local service.
