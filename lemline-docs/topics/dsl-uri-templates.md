---
title: URI Templates
---

# URI Templates

URI templates construct a URI from input data. Open Workflow supports simple string expansion
of `{variableName}` where a field accepts a URI template.

## Simple String Expansion

The template reads a top-level value with the same name as the text inside the braces.
For the example below, input `productId` selects the variable in the product path.
A missing or null value expands to an empty string. Strings, numbers, and booleans are
percent-encoded. An object or array faults with an expression error and status `400`.

<code-block lang="yaml" src="workflows/dsl-uri-templates/uri-template-product.yaml"/>

> **Lemline**
>
> HTTP calls expand templates in both `endpoint` and `endpoint.uri` using the task’s transformed input.

## Build a URI with an Expression

Use a [runtime expression](dsl-runtime-expressions.md) for nested data, workflow context, or
custom URL construction. Here jq’s `@uri` encodes the product ID before it is inserted
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
| `uri-template-product` | The garden hose, product `SKU-1`. |
| `dynamic-http-endpoint` | The garden hose, product `SKU-1`. |

After both workflows finish, check that both sent a request to `/products/SKU-1`.

```bash
sleep 6
curl -fsS http://127.0.0.1:8787/state
```

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
