---
title: HTTP Call
---
<!-- Examples are validated -->

# HTTP Call Task (`call: http`)

## Purpose

The HTTP Call task enables workflows to interact with external services and APIs over the standard HTTP/HTTPS protocol.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-call-http/usage-example.yaml"/>

## Additional Examples

### Example: POST Request with Body and Query Parameters

<code-block lang="yaml" src="workflows/dsl-call-http/example-post-request-with-body-and-query-parameters.yaml" include-lines="6-21"/>

### Example: Getting the Full Response Object

<code-block lang="yaml" src="workflows/dsl-call-http/example-getting-the-full-response-object.yaml" include-lines="6-22"/>

### Example: Getting Raw Response (e.g., for Binary Data)

<code-block lang="yaml" src="workflows/dsl-call-http/example-getting-raw-response-e-g-for-binary-data.yaml" include-lines="6-17"/>

## Configuration Options

The configuration for an HTTP call is provided within the `with` property of the `call: http` task.

### `with` (Object, Required)

*   **`method`** (String, Required): The HTTP request method (e.g., `get`, `post`, `put`, `delete`, `patch`).
*   **`endpoint`** (String | Object, Required): The target URL. Can be:
    *   A simple string URL.
    *   A [URI Template](dsl-uri-templates.md) string for simple variable substitution (e.g., `https://.../items/{itemId}`).
    *   An `Endpoint` object (inline definition or reference by name from `use.resources`) allowing specification of `uri` (String | Object, Required) and optional `authentication` (String | Object).
*   **`headers`** (Object, Optional): A key/value map of HTTP headers to include in the request.
*   **`body`** (Any, Optional): The HTTP request body. Can be any valid JSON/YAML structure or a string. The runtime typically serializes this based on the `Content-Type` header.
*   **`query`** (Object, Optional): A key/value map of query parameters to append to the URI.
*   **`output`** (String - `content` | `response` | `raw`, Optional, Default: `content`): Specifies the desired format of the task's `rawOutput`:
    *   `content` (Default): The deserialized body of the HTTP response.
    *   `response`: An object containing the full HTTP response details. It typically includes:
        *   `status` (Integer): The HTTP status code.
        *   `headers` (Object): A map of response headers.
        *   `body` (Any): The deserialized response body.
    *   `raw`: The raw response body, usually Base64 encoded if not plain text.
*   **`redirect`** (Boolean, Optional, Default: `false`): Controls handling of HTTP redirect status codes (3xx):
    *   `false` (Default): Only 2xx status codes are considered successful. 3xx codes will cause an error.
    *   `true`: Both 2xx and 3xx status codes are considered successful (implying redirects are followed, although the runtime behavior might vary). Errors are raised for other statuses (4xx, 5xx).

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