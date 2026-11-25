---
title: Function Call
---
<!-- Examples are validated -->

# Function Call Task (`call`)

## Purpose

The generic `Call` task enables the execution of a specified, named function within a workflow. This allows seamless integration with custom business logic, reusable workflow components, or functions defined externally (e.g., in a [Resource Catalog](dsl-resource-catalog.md)).

*Note: This describes the generic function call using a function name. For specific protocol interactions like HTTP, gRPC, OpenAPI, or AsyncAPI, refer to their dedicated task pages.*

## Usage Example

<code-block lang="yaml" src="workflows/dsl-call-function/call-address-function.yaml"/>

In this example, the `checkAddress` task calls a function named `validateAddress`, passing arguments derived from the current context or input using the `with` property.

## Additional Examples

### Example: Calling a Function with No Arguments

The named function has a fixed endpoint, so the caller can omit `with`.

<code-block lang="yaml" src="workflows/dsl-call-function/call-stock-function.yaml"/>

### Example: Calling a Catalog Function

Name the catalog in `use.catalogs` and call the product function by name and version.

<code-block lang="yaml" src="workflows/dsl-call-function/call-catalog-product-function.yaml"/>

## Configuration Options

### `call` (String, Required)

Specifies the **name** of the function to execute. This name must correspond to:

*   A function defined in the workflow's `use.functions` section.
*   A function available from an imported [Resource Catalog](dsl-resource-catalog.md).
*   A built-in function provided by the runtime (like `http`, `grpc`, etc., although using their specific task pages is recommended).

### `with` (Object, Optional)

A simple key/value map defining the arguments to pass to the called function. Values can be static or derived using [Runtime Expressions](dsl-runtime-expressions.md).

### Authentication

The generic function `call` task itself does not have a dedicated `authentication` property within its `with` block. If the function being called requires authentication to perform its internal operations, that logic must be handled *inside* the function's implementation (potentially using secrets or context passed via `with`).

If the function *definition* itself is hosted in a protected location (like a secured [Resource Catalog](dsl-resource-catalog.md)), authentication would be configured on the catalog's `endpoint` definition, not on the `call` task.

**Error Handling**: If authentication fails while trying to *access the function definition* from a protected catalog, the runtime should raise an `Authentication` or `Authorization` error related to accessing the catalog endpoint. Errors related to authentication *within* the called function's logic are the responsibility of the function itself to handle or raise appropriately.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the `Call` task is available for use within runtime expressions in the `with` arguments.
*   The `rawOutput` of the `Call` task is the result returned by the executed function.
*   Standard `output.as` and `export.as` process this function result.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f call-address-function.yaml
bin/lemline instance start examples call-address-function --input '{"address":{"street":"10 High Street","city":"London","country":"GB"}}'
bin/lemline definition post -f call-stock-function.yaml
bin/lemline instance start examples call-stock-function
bin/lemline definition post -f call-catalog-product-function.yaml
bin/lemline instance start examples call-catalog-product-function --input '{"productId":"SKU-2"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `call-address-function` | `{"valid":true}` |
| `call-stock-function` | The stock for `SKU-1` with `100` available units. |
| `call-catalog-product-function` | The product with ID `SKU-2`. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
