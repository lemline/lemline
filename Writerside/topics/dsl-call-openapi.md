---
title: OpenAPI Call
---

# OpenAPI Call Task (`call: openapi`)

## Purpose

The OpenAPI Call task enables workflows to interact with external RESTful APIs that are described by an [OpenAPI](https://www.openapis.org/) specification document.

This allows for type-safe interactions and leverages the API definition for validation and potentially generating client code or configurations.

## Usage Example

```yaml
document:
  dsl: '1.0.0' # Assuming alpha5 or later based on reference example
  namespace: test
  name: openapi-example
  version: '0.1.0'
do:
  - findAvailablePets:
      call: openapi
      with:
        # Reference to the OpenAPI document
        document: 
          endpoint: https://petstore.swagger.io/v2/swagger.json
        # ID of the operation to call (defined within the OpenAPI doc)
        operationId: findPetsByStatus
        # Parameters required by the operation
        parameters:
          status: available # Passed as query/path/header param based on OpenAPI spec
        # authentication: myApiAuth # Optional authentication
      # Output by default is the deserialized response body
      then: processAvailablePets
  - processAvailablePets:
      # ... uses the list of available pets from the response ...

## Additional Examples

### Example: Sending Different Parameter Types

```yaml
do:
  - updatePetDetails:
      call: openapi
      with:
        document: { endpoint: https://petstore.swagger.io/v2/swagger.json }
        operationId: updatePetWithForm
        parameters:
          petId: 123 # Path parameter (inferred from OpenAPI doc)
          name: "Fluffy Updated" # Form data parameter
          status: "pending" # Form data parameter
        authentication: petstore_auth # Defined elsewhere
      then: checkUpdateStatus
```

### Example: Getting the Full HTTP Response

```yaml
do:
  - getPetAndCheckHeaders:
      call: openapi
      with:
        document: { endpoint: https://petstore.swagger.io/v2/swagger.json }
        operationId: getPetById
        parameters:
          petId: "${ .targetPetId }"
        # Get the full underlying HTTP response
        output: response 
      then: analyzeHttpResponse
  - analyzeHttpResponse:
      # Input includes status, headers, body
      set:
        petData: "${ .body }"
        contentType: "${ .headers['Content-Type'] }"
```

## Configuration Options

The configuration for an OpenAPI call is provided within the `with` property of the `call: openapi` task.

### `with` (Object, Required)

*   **`document`** (Object, Required): Defines the location of the OpenAPI specification document (JSON or YAML). Contains:
    *   `endpoint` (Object, Required): Specifies the location with `uri` (String | Object, Required) and optional `authentication` (String | Object).
*   **`operationId`** (String, Required): The unique identifier (`operationId`) of the specific API operation to invoke, as defined within the OpenAPI `document`.
*   **`parameters`** (Object, Optional): A key/value map representing the parameters required by the specified `operationId`. The runtime uses the OpenAPI document to determine how each parameter should be sent (e.g., path, query, header, cookie, request body). Values can be static or derived using [Runtime Expressions](dsl-runtime-expressions.md).
*   **`authentication`** (String | Object, Optional): Authentication details (inline definition or reference by name from `use.authentications`) needed to access the API, often corresponding to security schemes defined in the OpenAPI document.
*   **`output`** (String - `content` | `response` | `raw`, Optional, Default: `content`): Specifies the desired format of the task's `rawOutput` (same behavior as the `output` property in the [HTTP Call Task](dsl-call-http.md)).
    *   When `response` is chosen, the output is an object typically containing:
        *   `status` (Integer): The HTTP status code.
        *   `headers` (Object): A map of response headers.
        *   `body` (Any): The deserialized response body.
*   **`redirect`** (Boolean, Optional, Default: `false`): Controls handling of HTTP redirect status codes (3xx) (same behavior as the `redirect` property in the [HTTP Call Task](dsl-call-http.md)).

### Authentication

The `with` object contains an optional `authentication` property. You can provide an inline definition or reference (by name) a policy from `use.authentications`. This authentication mechanism is used to access the API described by the OpenAPI document, often corresponding to `securitySchemes` defined within that document.

See the main [Authentication](dsl-authentication.md) page for details on defining authentication policies.

**Error Handling**: If the authentication specified fails *before* the underlying HTTP request is made (e.g., cannot retrieve OAuth2 token) or if the server rejects the credentials provided with a 401/403 status *specifically due to authentication*, the runtime should raise an `Authentication` (401) or `Authorization` (403) error. This halts the task unless caught by a `Try` block. General API errors (4xx/5xx) unrelated to the initial auth rejection are typically raised as `Communication` errors.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `with.parameters`.
*   The `rawOutput` of the task depends on the `with.output` setting (`content`, `response`, or `raw`), derived from the HTTP response of the underlying API call.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If the underlying API call fails (e.g., validation error based on OpenAPI spec, unhandled error status code), a `Communication` or potentially `Validation` error is raised, and the `then` directive is *not* followed (unless caught by `Try`). 