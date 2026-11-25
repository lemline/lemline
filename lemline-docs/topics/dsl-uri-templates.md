---
title: URI Templates
---

# URI Templates

URI templates construct a URI from input data. Open Workflow supports simple string expansion
of `{variableName}` where a field accepts a URI template.

## Simple String Expansion

The template reads a top-level value with the same name as the text inside the braces.
For the example below, input `productId` selects the variable in the product path.
A missing name expands to an empty string. A scalar value can be used for substitution.

<code-block lang="yaml" src="workflows/dsl-uri-templates/uri-template-product.yaml"/>

> **Lemline**
>
> The HTTP task sends the literal placeholder. For the example input below, the shop
> receives `/products/%7BproductId%7D` and returns HTTP 404. Use a runtime expression for expansion.
> [Issue #124](https://github.com/lemline/lemline/issues/124) tracks template substitution.

## Build a URI with an Expression

Use a [runtime expression](dsl-runtime-expressions.md) for nested data, workflow context, or when
a value needs explicit encoding. Here jq’s `@uri` encodes the product ID before it is inserted
into the URL.

<code-block lang="yaml" src="workflows/reference/dynamic-http-endpoint.yaml"/>

See the [HTTP call reference](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#http-call)
for the `endpoint` property.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f uri-template-product.yaml
bin/lemline instance start examples uri-template-product --input '{"productId":"SKU-1"}'
bin/lemline definition post -f dynamic-http-endpoint.yaml
bin/lemline instance start examples dynamic-http-endpoint --input '{"productId":"SKU-1"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `uri-template-product` | The task faults with HTTP `404` for the literal placeholder. |
| `dynamic-http-endpoint` | The garden hose, product `SKU-1`. |

Compare the request paths once both workflows have finished. Use the worker terminal to confirm the outcomes.

```bash
sleep 6
curl -fsS http://127.0.0.1:8787/state
```

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
