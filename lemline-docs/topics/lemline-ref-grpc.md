# gRPC Protocol Reference

This page describes the support of `call: grpc` tasks in Lemline.

## gRPC Support Status

Lemline does not execute `call: grpc` tasks yet. The Open Workflow DSL defines them (see [gRPC calls](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#grpc-call)), and Lemline parses workflows that contain them, but no runner can run such a task: do not use it in workflows you deploy.

There are no `lemline.grpc.*` runner settings.

## Alternative

Expose the service over HTTP (for example with a gRPC-JSON transcoding proxy) and call it with `call: http`:

<code-block lang="yaml" src="workflows/lemline-ref-grpc/alternative.yaml"/>

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f alternative.yaml
bin/lemline instance start examples grpc-alternative --input '{"sku":"SKU-1"}'
```

| Example | Expected result |
|---|---|
| `grpc-alternative` | The garden hose with its stock |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Supported Features](lemline-ref-supported-features.md)
