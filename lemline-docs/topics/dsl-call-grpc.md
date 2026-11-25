---
title: gRPC Call
---
<!-- Examples are validated -->

# gRPC Call Task (`call: grpc`)

> **Lemline**
>
> Lemline accepts these workflow definitions at registration. Starting an instance that reaches `call: grpc` faults with `Unknown task type: CallGRPC`. The tutorial shop serves the Protobuf contract over HTTP. It has no gRPC server.

## Purpose

The gRPC Call task enables workflows to interact with external systems using the high-performance [gRPC](https://grpc.io/) protocol.

## Usage Example

The Protobuf file declares the `shop.Inventory` service and its `GetStock` request and response messages.

<code-block lang="yaml" src="workflows/dsl-call-grpc/grpc-stock.yaml"/>

## Additional Examples

### Example: Using Authentication

A named bearer policy supplies credentials to the RPC service definition.

<code-block lang="yaml" src="workflows/dsl-call-grpc/grpc-stock-authenticated.yaml"/>

### Use an HTTP Endpoint

When a service exposes the same operation over HTTP, use `call: http`. The shop exposes this product lookup directly.

<code-block lang="yaml" src="workflows/dsl-call-grpc/grpc-http-product.yaml"/>

## Configuration Options

The configuration for a gRPC call is provided within the `with` property of the `call: grpc` task.

### `with` (Object, Required)

*   **`proto`** (Object, Required): Defines the location of the Protobuf (`.proto`) file. Contains:
    *   `endpoint` (Object, Required): Specifies the location with `uri` (String | Object, Required) and optional `authentication` (String | Object).
*   **`service`** (Object, Required): Defines the target gRPC service.
    *   **`name`** (String, Required): The fully qualified name of the gRPC service (e.g., `package.ServiceName`) as defined in the `.proto` file.
    *   **`host`** (String, Required): The hostname or IP address of the gRPC server.
    *   **`port`** (Integer, Optional): The port number of the gRPC server.
    *   **`authentication`** (String | Object, Optional): Authentication details (inline definition or reference by name from `use.authentications`) needed to connect to the service.
*   **`method`** (String, Required): The name of the specific RPC method to call within the service, as defined in the `.proto` file.
*   **`arguments`** (Object, Optional): A key/value map representing the arguments required by the specified gRPC `method`. Values can be static or derived using [Runtime Expressions](dsl-runtime-expressions.md).

### Authentication

The `service` object contains an optional `authentication` property where you can specify the authentication details needed to connect to the gRPC service. This can be an inline definition or a reference (by name) to a policy defined in `use.authentications`.

See the main [Authentication](dsl-authentication.md) page for details on supported schemes like OAuth2, Bearer, etc.

**Error Handling**: If the specified authentication fails during the connection attempt to the gRPC service (e.g., invalid token, credentials rejected), the runtime should typically raise an `Authentication` (401) or `Authorization` (403) error. This prevents the RPC method call and halts the task unless the error is caught by a `Try` block.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `with.arguments`.
*   The `rawOutput` of the task is the response message returned by the invoked gRPC method.
*   Standard `output.as` and `export.as` process this resulting response message.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If the gRPC call fails (e.g., connection error, method not found, server error), a `Communication` error is typically raised, and the `then` directive is *not* followed (unless caught by `Try`).

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Fetch the protobuf contract from the shop to inspect the inventory service definition.

```bash
curl --retry 10 --retry-connrefused --retry-delay 1 -fsS http://127.0.0.1:8787/inventory.proto
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f grpc-stock.yaml
bin/lemline instance start examples grpc-stock --input '{"sku":"SKU-1"}'
bin/lemline definition post -f grpc-stock-authenticated.yaml
bin/lemline instance start examples grpc-stock-authenticated --input '{"sku":"SKU-1"}'
bin/lemline definition post -f grpc-http-product.yaml
bin/lemline instance start examples grpc-http-product --input '{"sku":"SKU-1"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `grpc-stock` | Registration succeeds. The instance faults with `Unknown task type: CallGRPC`. |
| `grpc-stock-authenticated` | Registration succeeds. The instance faults with `Unknown task type: CallGRPC`. |
| `grpc-http-product` | The garden hose, `SKU-1`, with `100` available units. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
