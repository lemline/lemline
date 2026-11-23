---
title: gRPC Call
---
<!-- Examples are validated -->

# gRPC Call Task (`call: grpc`)

> **Lemline runtime:** `call: grpc` is not executed by Lemline yet.


## Purpose

The gRPC Call task enables workflows to interact with external systems using the high-performance [gRPC](https://grpc.io/) protocol.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-call-grpc/usage-example.yaml"/>

## Additional Examples

### Example: Using Authentication

<code-block lang="yaml" src="workflows/dsl-call-grpc/example-using-authentication.yaml"/>
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
