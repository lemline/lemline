# OpenAPI Reference

This page describes the support of `call: openapi` tasks in Lemline.

## OpenAPI Support Status

Lemline does not execute `call: openapi` tasks yet. The Open Workflow DSL defines them (see [OpenAPI calls](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#openapi-call)), and Lemline parses workflows that contain them, but no runner can run such a task: do not use it in workflows you deploy.

There are no `lemline.openapi.*` runner settings.

## Alternative

Call the operation's URL directly with `call: http`, using the method, path, query parameters and body described in the OpenAPI document:

<code-block lang="yaml" src="workflows/lemline-ref-openapi/alternative.yaml"/>

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f alternative.yaml
bin/lemline instance start examples openapi-alternative
```

| Example | Expected result |
|---|---|
| `openapi-alternative` | The first page of garden products, SKU-1 to SKU-3 |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Supported Features](lemline-ref-supported-features.md)
