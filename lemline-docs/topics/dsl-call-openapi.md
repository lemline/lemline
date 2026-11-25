---
title: OpenAPI Call
---
<!-- Examples are validated -->

# OpenAPI Call Task (`call: openapi`)

> **Lemline**
>
> Lemline accepts these workflow definitions at registration. Starting an instance that reaches `call: openapi` faults with `Unknown task type: CallOpenAPI` ([issue #45](https://github.com/lemline/lemline/issues/45)). Use `call: http` to invoke the operation directly.

## Purpose

The OpenAPI Call task enables workflows to interact with external RESTful APIs that are described by
an [OpenAPI](https://www.openapis.org/) specification document.

This allows for type-safe interactions and leverages the API definition for validation and potentially generating client
code or configurations.

## Usage Example

Use the shop’s OpenAPI description to select the `listProducts` operation and supply its `size` query parameter.

<code-block lang="yaml" src="workflows/dsl-call-openapi/openapi-list-products.yaml"/>

## Additional Examples

### Example: Sending a Path Parameter

Get one product by passing the path parameter named in the specification.

<code-block lang="yaml" src="workflows/dsl-call-openapi/openapi-product-by-id.yaml"/>

### Example: Getting the Full HTTP Response

Request the response envelope instead of just the deserialized body.

<code-block lang="yaml" src="workflows/dsl-call-openapi/openapi-product-response.yaml"/>

### Invoke the Operation with HTTP

This workflow calls the same products endpoint using the operation’s HTTP method and path.

<code-block lang="yaml" src="workflows/dsl-call-openapi/openapi-http-list-products.yaml"/>

## Configuration Options

The configuration for an OpenAPI call is provided within the `with` property of the `call: openapi` task.

### `with` (Object, Required)

* **`document`** (Object, Required): Defines the location of the OpenAPI specification document (JSON or YAML).
  Contains:
    * `endpoint` (Object, Required): Specifies the location with `uri` (String | Object, Required) and optional
      `authentication` (String | Object).
* **`operationId`** (String, Required): The unique identifier (`operationId`) of the specific API operation to invoke,
  as defined within the OpenAPI `document`.
* **`parameters`** (Object, Optional): A key/value map representing the parameters required by the specified
  `operationId`. The runtime uses the OpenAPI document to determine how each parameter should be sent (e.g., path,
  query, header, cookie, request body). Values can be static or derived
  using [Runtime Expressions](dsl-runtime-expressions.md).
* **`authentication`** (String | Object, Optional): Authentication details (inline definition or reference by name from
  `use.authentications`) needed to access the API, often corresponding to security schemes defined in the OpenAPI
  document.
* **`output`** (String - `content` | `response` | `raw`, Optional, Default: `content`): Specifies the desired format of
  the task's `rawOutput` (same behavior as the `output` property in the [HTTP Call Task](dsl-call-http.md)).
    * When `response` is chosen, the output is an object typically containing:
        * `status` (Integer): The HTTP status code.
        * `headers` (Object): A map of response headers.
        * `body` (Any): The deserialized response body.
* **`redirect`** (Boolean, Optional, Default: `false`): Controls handling of HTTP redirect status codes (3xx) (same
  behavior as the `redirect` property in the [HTTP Call Task](dsl-call-http.md)).

### Authentication

The `with` object contains an optional `authentication` property. You can provide an inline definition or reference (by
name) a policy from `use.authentications`. This authentication mechanism is used to access the API described by the
OpenAPI document, often corresponding to `securitySchemes` defined within that document.

See the main [Authentication](dsl-authentication.md) page for details on defining authentication policies.

**Error Handling**: If the authentication specified fails *before* the underlying HTTP request is made (e.g., cannot
retrieve OAuth2 token) or if the server rejects the credentials provided with a 401/403 status *specifically due to
authentication*, the runtime should raise an `Authentication` (401) or `Authorization` (403) error. This halts the task
unless caught by a `Try` block. General API errors (4xx/5xx) unrelated to the initial auth rejection are typically
raised as `Communication` errors.

### Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `with.parameters`.
*   The `rawOutput` of the task depends on the `with.output` setting (`content`, `response`, or `raw`), derived from the HTTP response of the underlying API call.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If the underlying API call fails (e.g., validation error based on OpenAPI spec, unhandled error status code), a `Communication` or potentially `Validation` error is raised, and the `then` directive is *not* followed (unless caught by `Try`).

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f openapi-list-products.yaml
bin/lemline instance start examples openapi-list-products
bin/lemline definition post -f openapi-product-by-id.yaml
bin/lemline instance start examples openapi-product-by-id --input '{"productId":"SKU-1"}'
bin/lemline definition post -f openapi-product-response.yaml
bin/lemline instance start examples openapi-product-response --input '{"productId":"SKU-1"}'
bin/lemline definition post -f openapi-http-list-products.yaml
bin/lemline instance start examples openapi-http-list-products
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `openapi-list-products` | Registration succeeds. The instance faults with `Unknown task type: CallOpenAPI`. |
| `openapi-product-by-id` | Registration succeeds. The instance faults with `Unknown task type: CallOpenAPI`. |
| `openapi-product-response` | Registration succeeds. The instance faults with `Unknown task type: CallOpenAPI`. |
| `openapi-http-list-products` | The first page of garden products, with IDs `SKU-1`, `SKU-2`, and `SKU-3`. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
