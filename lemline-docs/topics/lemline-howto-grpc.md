---
title: How to call gRPC functions
---

# How to describe a gRPC call

Open Workflow defines `call: grpc`. Lemline registers such a definition, but it has no gRPC executor yet: an
instance faults at the gRPC task with `Unknown task type: CallGRPC`. The examples below describe a stock service of
the tutorial shop that exists only as an illustration: the shop has no gRPC server. Until Lemline supports gRPC,
call an HTTP equivalent of the service, as shown at the end of this page. See
[Supported Features](lemline-ref-supported-features.md).

To check the registration, complete [the SQLite setup](lemline-tutorial-setup.md), keep its worker running and use a
second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

## Define a unary call

Identify the protobuf resource, service and method. Set `arguments` to the request
message fields.

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-grpc-call.yaml"/>

Save it as `basic-grpc-call.yaml`:

```bash
bin/lemline definition post -f basic-grpc-call.yaml
bin/lemline instance start examples grpc-get-stock --input '{"sku":"SKU-1"}'
```

The definition registers. The instance faults with a `runtime` error titled `Unknown task type: CallGRPC`.

## Configure authentication

Attach a policy to the service where the executing runtime supports it. This one sends a bearer token read from the
`apiToken` secret:

<code-block lang="yaml" src="workflows/dsl-call-grpc/example-using-authentication.yaml"/>

Save it as `example-using-authentication.yaml`. Register it to check the definition; it cannot run on Lemline either:

```bash
bin/lemline definition post -f example-using-authentication.yaml
```

## Call an HTTP equivalent

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the
Lemline source checkout. Its product endpoint returns the same stock information over HTTP:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

<code-block lang="yaml" src="workflows/lemline-ref-grpc/alternative.yaml"/>

Save it as `alternative.yaml`:

```bash
bin/lemline definition post -f alternative.yaml
bin/lemline instance start examples grpc-alternative --input '{"sku":"SKU-1"}'
```

The instance completes with the product and its stock, `{"id":"SKU-1","name":"Garden hose","category":"garden","price":25,"available":100}`.
Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See the [gRPC Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#grpc-call) for configuration fields.
