# gRPC Protocol Reference

This page describes the support of `call: grpc` tasks in Lemline.

## gRPC Support Status

Lemline does not execute `call: grpc` tasks yet. The Open Workflow DSL defines them (see [gRPC calls](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl-reference.md#grpc-call)), and Lemline parses workflows that contain them, but no runner can run such a task: do not use it in workflows you deploy.

There are no `lemline.grpc.*` runner settings.

## Alternative

Expose the service over HTTP (for example with a gRPC-JSON transcoding proxy) and call it with `call: http`:

<code-block lang="yaml" src="workflows/lemline-ref-grpc/alternative.yaml" include-lines="6-13"/>

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Supported Features](lemline-ref-supported-features.md)
