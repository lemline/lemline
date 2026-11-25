---
title: HTTP Call
---
<!-- Examples are validated -->

# HTTP Call Task (`call: http`)

## Purpose

The HTTP Call task enables workflows to interact with external services and APIs over the standard HTTP/HTTPS protocol.

## Usage Example

Read a customer record and select its ID and name from the deserialized response body.

<code-block lang="yaml" src="workflows/dsl-call-http/http-read-customer.yaml"/>

## Additional Examples

### Example: POST Request with Body and Query Parameters

Send a new customer as JSON, with `source=workflow` in the query string.

<code-block lang="yaml" src="workflows/dsl-call-http/http-create-customer.yaml"/>

### Example: Getting the Full Response Object

Follow the redirect from `/old-stock` to `/stock` and read the final status and payload.

> **Lemline**
>
> The task returns the status as `statusCode` and the payload as `content`.

<code-block lang="yaml" src="workflows/dsl-call-http/http-full-response.yaml"/>

### Example: Getting the Raw Response

This example asks for the raw stock response and parses the JSON after decoding it.

> **Lemline**
>
> The worker returns raw response bytes encoded in Base64.

<code-block lang="yaml" src="workflows/dsl-call-http/http-decode-body.yaml"/>

## Configuration Options

The configuration for an HTTP call is provided within the `with` property of the `call: http` task.

### `with` (Object, Required)

*   **`method`** (String, Required): The HTTP request method (e.g., `get`, `post`, `put`, `delete`, `patch`).
*   **`endpoint`** (String | Object, Required): The target URL. Can be:
    *   A simple string URL.
    *   A [URI Template](dsl-uri-templates.md) string for simple variable substitution (for example, a path ending in `/products/{productId}`).
    *   An `Endpoint` object (inline definition or reference by name from `use.resources`) allowing specification of `uri` (String | Object, Required) and optional `authentication` (String | Object).
*   **`headers`** (Object, Optional): A key/value map of HTTP headers to include in the request.
*   **`body`** (Any, Optional): The HTTP request body. Can be any valid JSON/YAML structure or a string. The runtime typically serializes this based on the `Content-Type` header.
*   **`query`** (Object, Optional): A key/value map of query parameters to append to the URI.
*   **`output`** (String - `content` | `response` | `raw`, Optional, Default: `content`): Specifies the desired format of the task's `rawOutput`:
    *   `content` (Default): The deserialized body of the HTTP response.
    *   `response`: An object containing the full HTTP response details. It typically includes:
        *   The response status code.
        *   The response headers.
        *   The deserialized response body.
    *   `raw`: The response body before deserialization.
*   **`redirect`** (Boolean, Optional, Default: `false`): Controls handling of HTTP redirect status codes (3xx):
    *   `false` (Default): The client leaves redirects unfollowed.
    *   `true`: The client follows redirects.

### Authentication

Authentication for HTTP calls is typically defined within the `Endpoint` object if you are using the object structure for the `endpoint` property. This allows you to associate an authentication policy (defined globally in `use.authentications` or inline) directly with the target URI.

See the main [Authentication](dsl-authentication.md) page for details on defining different authentication schemes (Basic, Bearer, OAuth2, etc.).

**Error Handling**: If the authentication process itself fails (e.g., invalid credentials, unable to obtain OAuth2 token, token rejected by the server *before* the main request is processed), the runtime should typically raise an `Authentication` (401) or `Authorization` (403) error. This would prevent the actual HTTP request from being sent and halt the task unless the error is caught by a `Try` block.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `with` properties (`endpoint`, `headers`, `body`, `query`).
*   The `rawOutput` of the task depends on the `with.output` setting (`content`, `response`, or `raw`).
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If the HTTP call results in an unhandled error status code (e.g., 4xx, 5xx, or 3xx if `redirect: false`), a `Communication` error is typically raised, and the `then` directive is *not* followed (unless caught by `Try`).

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f http-read-customer.yaml
bin/lemline instance start examples http-read-customer --input '{"customerId":"C-100"}'
bin/lemline definition post -f http-create-customer.yaml
bin/lemline instance start examples http-create-customer --input '{"name":"Hedy Lamarr","email":"hedy@example.com"}'
bin/lemline definition post -f http-full-response.yaml
bin/lemline instance start examples http-full-response
bin/lemline definition post -f http-decode-body.yaml
bin/lemline instance start examples http-decode-body
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `http-read-customer` | `{"customerId":"C-100","name":"Ada Lovelace"}` |
| `http-create-customer` | `{"customerId":"C-901","name":"Hedy Lamarr"}` |
| `http-full-response` | `{"status":200,"sku":"SKU-1","available":100}` |
| `http-decode-body` | `{"sku":"SKU-1"}` |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
