---
title: How to use OpenAPI-defined services
---

# How to describe an OpenAPI call

Open Workflow defines `call: openapi`. Lemline can load this configuration, while
execution requires an implementation that supports OpenAPI calls. See
[Supported Features](lemline-ref-supported-features.md).

## Choose an operation

Set `with.document.endpoint` to the OpenAPI document, select an operation using
`operationId`, and pass its parameters in `parameters`.

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-openapi-call.yaml"/>

See the [OpenAPI Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#openapi-call) for configuration options. With Lemline,
call the specific service operation using [an HTTP task](lemline-howto-http.md).
