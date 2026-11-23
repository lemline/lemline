---
title: How to call gRPC functions
---

# How to describe a gRPC call

Open Workflow defines `call: grpc`. Lemline can load this configuration, while
execution requires an implementation that supports gRPC calls. See
[Supported Features](lemline-ref-supported-features.md).

## Define a unary call

Identify the protobuf resource, service and method. Set `arguments` to the request
message fields.

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-grpc-call.yaml"/>

## Configure authentication

Attach a policy to the endpoint where the executing runtime supports it.

<code-block lang="yaml" src="workflows/dsl-call-grpc/example-using-authentication.yaml"/>

See the [gRPC Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#grpc-call) for configuration fields and
[HTTP Calls](lemline-howto-http.md) for an alternative integration supported by Lemline.
