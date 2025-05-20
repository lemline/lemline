# Lemline Integration Examples

This document provides examples of integrating Lemline workflows with external services using various protocols and standards. These integration patterns enable workflows to interact with APIs, services, and messaging systems.

## HTTP/REST API Integration

HTTP/REST is one of the most common integration methods for connecting workflows to external services.

### Basic HTTP Call

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: call-http-shorthand-endpoint
  version: '0.1.0'
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
```

This example demonstrates:
- A simple HTTP GET request
- Path parameter interpolation using `{petId}`

### HTTP with Query Parameters

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: http-query-params
  version: '1.0.0'
input:
  schema:
    format: json
    document:
      type: object
      required:
        - searchQuery
      properties:
        searchQuery:
          type: string
do:
  - searchStarWarsCharacters:
      call: http
      with:
        method: get
        endpoint: https://swapi.dev/api/people/
        query:
          search: ${.searchQuery}
```

This example shows:
- Using query parameters with the `query` property
- Dynamic query values using expressions
- Input schema validation

### HTTP with Authentication

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: oauth2-authentication
  version: '0.1.0'
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint:
          uri: https://petstore.swagger.io/v2/pet/{petId}
          authentication:
            oauth2:
              authority: http://keycloak/realms/fake-authority
              endpoints: #optional
                token: /auth/token #defaults to /oauth2/token
                introspection: /auth/introspect #defaults to /oauth2/introspect
              grant: client_credentials
              client:
                id: workflow-runtime-id
                secret: workflow-runtime-secret
```

This example demonstrates:
- OAuth2 authentication for HTTP calls
- Client credentials grant type
- Custom token endpoints

Other supported authentication methods include:
- Bearer token
- Basic authentication
- API key
- OIDC (OpenID Connect)

## OpenAPI Integration

OpenAPI integration allows workflows to interact with services described by OpenAPI specifications.

### Basic OpenAPI Call

```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: openapi-example
  version: '0.1.0'
do:
  - findPet:
      call: openapi
      with:
        document: 
          endpoint: https://petstore.swagger.io/v2/swagger.json
        operationId: findPetsByStatus
        parameters:
          status: available
```

This example demonstrates:
- Referencing an OpenAPI specification document
- Calling an operation by its `operationId`
- Passing operation parameters

Benefits of using OpenAPI integration:
- Type-safe API interactions
- Automatic request/response serialization
- Simplified parameter handling
- API discoverability

## gRPC Integration

gRPC integration enables workflows to communicate with gRPC services.

### Basic gRPC Call

```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: grpc-example
  version: '0.1.0'
do:
  - greet:
      call: grpc
      with:
        proto: 
          endpoint: file://app/greet.proto
        service:
          name: GreeterApi.Greeter
          host: localhost
          port: 5011
        method: SayHello
        arguments:
          name: ${ .user.preferredDisplayName }
```

This example demonstrates:
- Referencing a Protocol Buffers (.proto) file
- Specifying the service name, host, and port
- Calling a specific method
- Passing arguments from workflow data

Benefits of using gRPC:
- Efficient binary communication
- Strongly typed contracts
- Support for streaming (unary, server, client, or bidirectional)
- Built-in compression

## AsyncAPI Integration

AsyncAPI integration allows workflows to interact with event-driven and message-based systems.

### Publishing Messages

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: bearer-auth
  version: '0.1.0'
do:
  - findPet:
      call: asyncapi
      with:
        document:
          endpoint: https://fake.com/docs/asyncapi.json
        operation: findPetsByStatus
        server:
          name: staging
        message:
          payload:
            petId: ${ .pet.id }
        authentication:
          bearer:
            token: ${ .token }
```

This example demonstrates:
- Referencing an AsyncAPI specification
- Publishing a message to a specific server
- Specifying the message payload
- Including authentication

### Subscribing to Messages

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: bearer-auth
  version: '0.1.0'
do:
  - getNotifications:
      call: asyncapi
      with:
        document:
          endpoint: https://fake.com/docs/asyncapi.json
        operation: getNotifications
        protocol: ws
        subscription:
          filter: '${ .correlationId == $context.userId and .payload.from.firstName == $context.contact.firstName and .payload.from.lastName == $context.contact.lastName }'
          consume:
            amount: 5
```

This example demonstrates:
- Subscribing to messages using WebSocket protocol
- Applying a filter expression to incoming messages
- Consuming a specific amount of messages

### Other Subscription Patterns

AsyncAPI integration supports various message consumption patterns:

1. **Consume a specific amount**:
   ```yaml
   subscription:
     consume:
       amount: 5
   ```

2. **Consume until a condition is met**:
   ```yaml
   subscription:
     consume:
       until: '${ .status == "completed" }'
   ```

3. **Consume for a specific duration**:
   ```yaml
   subscription:
     consume:
       for:
         seconds: 60
   ```

4. **Consume while a condition is true**:
   ```yaml
   subscription:
     consume:
       while: '${ .status == "processing" }'
   ```

5. **Consume forever with foreach processing**:
   ```yaml
   subscription:
     consume:
       forever:
         foreach:
           do:
             - processMessage:
                 set:
                   result: ${ .payload.data }
   ```

## Custom Function Integration

Custom functions allow reusing integration patterns across workflows.

### Inline Custom Function

```yaml
document:
  dsl: '1.0.0'
  namespace: samples
  name: call-custom-function-inline
  version: '0.1.0'
use:
  functions:
    getPetById:
      input:
        schema:
          document:
            type: object
            properties:
              petId:
                type: string
            required: [ petId ]
      call: http
      with:
        method: get
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
do:
  - getPet:
      call: getPetById
      with:
        petId: 69
```

This example demonstrates:
- Defining a custom function inline
- Adding input schema validation
- Reusing the function within the workflow

### Cataloged Function

Custom functions can also be defined in a separate catalog and referenced across multiple workflows:

```yaml
document:
  dsl: '1.0.0'
  namespace: samples
  name: call-custom-function-cataloged
  version: '0.1.0'
use:
  catalogs:
    - endpoint: file://catalog.json
do:
  - getPet:
      call: petstore.getPetById
      with:
        petId: 69
```

This enables:
- Function reuse across multiple workflows
- Centralized integration pattern maintenance
- Consistent API access patterns

## Best Practices for Integrations

When implementing integration patterns in Lemline:

1. **Error handling**: Always implement error handling for external service calls using try-catch blocks
2. **Timeouts**: Set appropriate timeouts for service calls to prevent workflow hangs
3. **Retry strategies**: Use retry mechanisms with backoff for transient failures
4. **Authentication**: Securely manage credentials using secrets management
5. **Data validation**: Validate inputs and outputs using schemas
6. **Monitoring**: Add appropriate logging for external service interactions
7. **Idempotency**: Design integrations to be idempotent to handle retries safely

## Complex Integration Example

This example combines multiple integration patterns:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: complex-integration
  version: '1.0.0'
use:
  catalogs:
    - endpoint: file://catalog.json
do:
  - getCustomerData:
      call: crm.getCustomerById
      with:
        customerId: ${ .customerId }
  - processPurchaseOrder:
      try:
        - validateInventory:
            call: openapi
            with:
              document:
                endpoint: https://inventory.example.com/api/openapi.json
              operationId: checkItemsAvailability
              parameters:
                items: ${ .orderItems }
        - processPayment:
            call: http
            with:
              method: post
              endpoint: https://payments.example.com/api/transactions
              headers:
                Content-Type: application/json
                Authorization: Bearer ${ .paymentToken }
              body: ${ { customerId: .customerId, amount: .totalAmount, items: .orderItems } }
        - notifyShipping:
            call: asyncapi
            with:
              document:
                endpoint: https://messaging.example.com/asyncapi.json
              operation: publishShippingRequest
              server:
                name: production
              message:
                payload:
                  orderId: ${ .orderId }
                  customer: ${ .customerData }
                  items: ${ .orderItems }
      catch:
        - when: ${ $error.type == "https://example.com/errors/payment-declined" }
          do:
            - notifyCustomer:
                call: crm.sendNotification
                with:
                  customerId: ${ .customerId }
                  message: "Your payment was declined. Please update payment information."
        - default:
            do:
              - logError:
                  call: http
                  with:
                    method: post
                    endpoint: https://logging.example.com/api/errors
                    body: ${ $error }
```

This complex example demonstrates:
- Multiple integration methods (HTTP, OpenAPI, AsyncAPI)
- Custom function calls from a catalog
- Error handling for different scenarios
- Data transformation between services
- Authentication with bearer tokens

## Conclusion

These integration examples showcase Lemline's ability to connect with various external services and systems. By leveraging these patterns, you can build workflows that:

- Integrate with diverse APIs and services
- Process events and messages
- Reuse integration logic across workflows
- Handle errors and retries gracefully
- Ensure secure communication with external systems

For examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.