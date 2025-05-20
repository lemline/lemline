# OpenAPI Reference

This reference documents all aspects of OpenAPI support in Lemline, including configuration, features, and advanced usage.

## OpenAPI Support Overview

Lemline provides comprehensive OpenAPI integration, enabling workflows to:

1. **Discover API Operations**: Automatically use API specifications 
2. **Validate Requests**: Ensure request parameters match API specifications
3. **Type-Safe Responses**: Parse responses according to schema definitions
4. **Model-Driven Workflows**: Build workflows based on API models
5. **API Authentication**: Handle authentication according to specifications

## Basic OpenAPI Call

The `callOpenAPI` task provides OpenAPI integration:

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123
```

## OpenAPI Specification

### Specification Sources

Lemline can load OpenAPI specifications from:

1. **URL**: Remote specification
   ```yaml
   api: "https://petstore.swagger.io/v2/swagger.json"
   ```

2. **File Path**: Local specification file
   ```yaml
   api: "/path/to/petstore.yaml"
   ```

3. **Resource Catalog**: Reference to a catalog resource
   ```yaml
   api: "petstore"  # References a defined resource
   ```

### Specification Formats

Lemline supports both YAML and JSON formats for OpenAPI specifications:

- OpenAPI 3.0.x
- OpenAPI 3.1.x
- Swagger 2.0

### Specification Validation

OpenAPI specifications are validated on loading:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  validateSpecification: true  # Default
  parameters:
    petId: 123
```

### Specification Caching

Specifications are cached for performance:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  cacheSpecification: true  # Default
  parameters:
    petId: 123
```

## Operation Selection

### Operation ID

Select operations using the operationId:

```yaml
operation: "getPetById"
```

### Method and Path

Alternatively, select operations using HTTP method and path:

```yaml
operation:
  method: "GET"
  path: "/pet/{petId}"
```

### Multiple Operations

Execute multiple operations in sequence:

```yaml
- managePet:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operations:
        - id: "getPetById"
          parameters:
            petId: 123
          as: "existingPet"
        - id: "updatePet"
          parameters:
            pet: 
              id: 123
              name: "${ .existingPet.name }"
              status: "sold"
```

## Parameters

### Path Parameters

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123  # Path parameter from {petId}
```

### Query Parameters

```yaml
- findPetsByStatus:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "findPetsByStatus"
      parameters:
        status: "available"  # Query parameter ?status=available
```

### Request Body

```yaml
- addPet:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "addPet"
      parameters:
        pet:  # Request body
          name: "Fluffy"
          photoUrls: ["https://example.com/fluffy.jpg"]
          status: "available"
```

### Header Parameters

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123
        apiKey: "your-api-key"  # Header parameter
```

### Form Data

```yaml
- uploadPetImage:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "uploadFile"
      parameters:
        petId: 123
        additionalMetadata: "Pet photo"
        file: "${ .imageData }"  # Form file upload
```

### Complex Objects

```yaml
- updatePet:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "updatePet"
      parameters:
        pet:
          id: 123
          name: "Fluffy"
          category:
            id: 1
            name: "Dogs"
          tags:
            - id: 1
              name: "friendly"
            - id: 2
              name: "trained"
          status: "available"
```

## Parameter Validation

Lemline validates parameters against the OpenAPI specification:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "addPet"
  validateParameters: true  # Default
  parameters:
    pet:
      name: "Fluffy"
      photoUrls: ["https://example.com/fluffy.jpg"]
      status: "available"
```

Parameter validation ensures:
- **Required parameters** are provided
- **Parameter types** match schema definitions
- **Enum values** are valid
- **Format constraints** are met
- **Pattern validation** is enforced

## Response Handling

### Response Structure

OpenAPI responses provide:

```
status: HTTP status code (number)
headers: Response headers (object)
body: Response body (parsed according to schema)
```

### Response Parsing

Responses are automatically parsed according to the response schema:

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123
    # Response is automatically parsed and stored in task output
```

### Response Schema Validation

Responses are validated against the schema:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  parameters:
    petId: 123
  validateResponse: true  # Default
```

### Response Transformation

Extract specific parts of the response:

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123
      output:
        from: ".name"  # Extract name field from response
        as: "petName"  # Store as petName variable
```

### Error Responses

Error responses (non-2xx) are mapped to errors:

```yaml
- getPetById:
    try:
      do:
        - callPetStore:
            callOpenAPI:
              api: "https://petstore.swagger.io/v2/swagger.json"
              operation: "getPetById"
              parameters:
                petId: 999  # Non-existent pet
      catch:
        - error:
            with:
              status: 404  # Not Found
            as: "apiError"
          do:
            - handleNotFound:
                set:
                  errorMessage: "Pet not found: ${ .apiError.details }"
```

## Authentication

### API Key Authentication

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123
      auth:
        apiKey:
          headerName: "api_key"
          key:
            secret: "petstore.api.key"
```

### Basic Authentication

```yaml
auth:
  basic:
    username: "user"
    password:
      secret: "api.password"
```

### OAuth2 Authentication

```yaml
auth:
  oauth2:
    grantType: "client_credentials"
    tokenUrl: "https://auth.example.com/token"
    clientId: "client-id"
    clientSecret:
      secret: "oauth.client.secret"
    scopes:
      - "read:pets"
      - "write:pets"
```

### Security Scheme Selection

Select specific security schemes from the specification:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "updatePet"
  parameters:
    pet:
      id: 123
      name: "Fluffy"
      status: "available"
  security:
    petstore_auth:  # Name of security scheme in the specification
      scopes:
        - "write:pets"
```

## Server Selection

### Default Server

By default, Lemline uses the first server in the API specification:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  parameters:
    petId: 123
```

### Specific Server

Select a specific server by index or URL:

```yaml
# By index
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  server: 1  # Use second server in the specification
  parameters:
    petId: 123

# By URL
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  server: "https://petstore-dev.swagger.io/v2"  # Specific server URL
  parameters:
    petId: 123
```

### Server Variables

Provide values for server variables:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  server: "https://{tenant}.swagger.io/{basePath}"
  serverVariables:
    tenant: "petstore-dev"
    basePath: "v2"
  parameters:
    petId: 123
```

## Operation Extensions

### Timeouts

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  parameters:
    petId: 123
  timeout: PT30S  # 30 second timeout
```

### Follow Redirects

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  parameters:
    petId: 123
  followRedirects: true  # Default
```

### Custom Headers

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  parameters:
    petId: 123
  headers:
    X-Custom-Header: "custom-value"
    X-Request-ID: "${ uuid() }"
```

## Catalog Integration

### API Catalog Definition

Define APIs in the catalog:

```yaml
use:
  catalogs:
    - name: "apis"
      resources:
        - name: "petstore"
          type: "openapi"
          location: "https://petstore.swagger.io/v2/swagger.json"
```

### Using Catalog APIs

Reference catalog APIs in operations:

```yaml
- getPetById:
    callOpenAPI:
      api: "petstore"  # Reference to catalog resource
      operation: "getPetById"
      parameters:
        petId: 123
```

## OpenAPI Client Configuration

Global OpenAPI client configuration:

```properties
# Specification handling
lemline.openapi.cache-enabled=true
lemline.openapi.cache-max-size=100
lemline.openapi.cache-expiry=PT1H

# Validation settings
lemline.openapi.validate-specification=true
lemline.openapi.validate-parameters=true
lemline.openapi.validate-response=true

# Default timeouts
lemline.openapi.connect-timeout=PT5S
lemline.openapi.read-timeout=PT30S
lemline.openapi.call-timeout=PT60S
```

## Advanced Features

### Content Type Negotiation

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "addPet"
  parameters:
    pet:
      name: "Fluffy"
      status: "available"
  contentType: "application/json"  # Request content type
  accept: "application/json"       # Response content type
```

### Schema References

Resolve references in schemas:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "addPet"
  parameters:
    pet: "${ .petData }"  # Reference to schema defined in API
```

### Schema Reuse

Reuse schema objects across operations:

```yaml
- managePets:
    do:
      - createPet:
          callOpenAPI:
            api: "petstore"
            operation: "addPet"
            parameters:
              pet:
                name: "Fluffy"
                photoUrls: ["https://example.com/fluffy.jpg"]
                status: "available"
            as: "newPet"
      
      - updatePet:
          callOpenAPI:
            api: "petstore"
            operation: "updatePet"
            parameters:
              pet:
                id: "${ .newPet.id }"
                name: "Fluffy Jr."
                photoUrls: "${ .newPet.photoUrls }"
                status: "available"
```

### Documentation Extraction

Extract documentation from the OpenAPI specification:

```yaml
callOpenAPI:
  api: "https://petstore.swagger.io/v2/swagger.json"
  operation: "getPetById"
  parameters:
    petId: 123
  extension:
    extractDocs: true  # Include operation documentation in result
```

## OpenAPI Request Examples

### Get Resource

```yaml
- getPetById:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "getPetById"
      parameters:
        petId: 123
```

### Create Resource

```yaml
- addPet:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "addPet"
      parameters:
        pet:
          name: "Fluffy"
          photoUrls: ["https://example.com/fluffy.jpg"]
          category:
            id: 1
            name: "Dogs"
          tags:
            - id: 1
              name: "friendly"
          status: "available"
```

### Update Resource

```yaml
- updatePet:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "updatePet"
      parameters:
        pet:
          id: 123
          name: "Fluffy"
          status: "sold"
```

### Delete Resource

```yaml
- deletePet:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "deletePet"
      parameters:
        petId: 123
        apiKey: "special-key"
```

### File Upload

```yaml
- uploadPetImage:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "uploadFile"
      parameters:
        petId: 123
        additionalMetadata: "Pet photo"
        file: "${ .imageData }"
```

### Search/Filter

```yaml
- findPetsByStatus:
    callOpenAPI:
      api: "https://petstore.swagger.io/v2/swagger.json"
      operation: "findPetsByStatus"
      parameters:
        status: "available"
```

## Related Resources

- [OpenAPI Integration](lemline-howto-openapi.md)
- [HTTP Protocol Reference](lemline-ref-http.md)
- [Authentication Reference](lemline-ref-auth.md)
- [Resilience Patterns](dsl-resilience-patterns.md)