---
title: How to use OpenAPI-defined services
---

# How to describe an OpenAPI call

Open Workflow defines `call: openapi`. Lemline registers such a definition, but it has no OpenAPI executor yet:
an instance faults at the OpenAPI task with `Unknown task type: CallOpenAPI`. Until it does, call the operation
with [an HTTP task](lemline-howto-http.md), as shown at the end of this page. See
[Supported Features](lemline-ref-supported-features.md).

## Prepare the example

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Start the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source
checkout; it publishes an OpenAPI document of its catalog at `http://127.0.0.1:8787/openapi.json`. Run the other
commands from a second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

## Choose an operation

Set `with.document.endpoint` to the OpenAPI document, select an operation using
`operationId`, and pass its parameters in `parameters`.

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-openapi-call.yaml"/>

Save it as `basic-openapi-call.yaml`:

```bash
bin/lemline definition post -f basic-openapi-call.yaml
bin/lemline instance start examples openapi-list-products
```

The definition registers. The instance faults with a `runtime` error titled `Unknown task type: CallOpenAPI`.

## Call the same operation over HTTP

The `listProducts` operation is `GET /products` with a `category` query parameter. This HTTP task sends the same request:

<code-block lang="yaml" src="workflows/lemline-ref-openapi/alternative.yaml"/>

Save it as `alternative.yaml`:

```bash
bin/lemline definition post -f alternative.yaml
bin/lemline instance start examples openapi-alternative
```

The instance completes with the first page of garden products: `items` lists SKU-1, SKU-2 and SKU-3, and `hasMore`
is `false`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See the [OpenAPI Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#openapi-call) for configuration options.
