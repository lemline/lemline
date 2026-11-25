# Lemline Integration Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 authentication. The Lemline runtime does not execute these yet: such a task fails when it runs. Write those calls with `call: http` and `basic` or `bearer` authentication to run them on Lemline. URI template placeholders such as `{petId}` are not expanded either: build the URI with a runtime expression, for example `${ "https://petstore.swagger.io/v2/pet/" + (.petId | tostring) }`.


This document provides examples of integrating Lemline workflows with external services using various protocols and standards. These integration patterns enable workflows to interact with APIs, services, and messaging systems.

## HTTP/REST API Integration

HTTP/REST is one of the most common integration methods for connecting workflows to external services.

### Basic HTTP Call

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-http-call.yaml"/>

This example demonstrates:
- A simple HTTP GET request
- A URI built from the `petId` workflow input with a runtime expression

### HTTP with Query Parameters

<code-block lang="yaml" src="workflows/lemline-examples-integrations/http-with-query-parameters.yaml"/>

This example shows:
- Using query parameters with the `query` property
- Dynamic query values using expressions
- Input schema validation

### HTTP with Authentication

<code-block lang="yaml" src="workflows/lemline-examples-integrations/http-with-authentication.yaml"/>

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

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-openapi-call.yaml"/>

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

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-grpc-call.yaml"/>

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

<code-block lang="yaml" src="workflows/lemline-examples-integrations/publishing-messages.yaml"/>

This example demonstrates:
- Referencing an AsyncAPI specification
- Publishing a message to a specific server
- Specifying the message payload
- Including authentication

### Subscribing to Messages

<code-block lang="yaml" src="workflows/lemline-examples-integrations/subscribing-to-messages.yaml"/>

This example demonstrates:
- Subscribing to messages using WebSocket protocol
- Applying a filter expression to incoming messages
- Consuming a specific amount of messages

### Other Subscription Patterns

AsyncAPI integration supports various message consumption patterns:

1. **Consume a specific amount**:

   <code-block lang="yaml" src="workflows/reference/asyncapi-consume-amount.yaml" include-lines="13-15"/>

2. **Consume until a condition is met**:

   <code-block lang="yaml" src="workflows/reference/asyncapi-consume-until.yaml" include-lines="13-15"/>

3. **Consume while a condition is true**:

   <code-block lang="yaml" src="workflows/reference/asyncapi-consume-while.yaml" include-lines="13-15"/>

Add `consume.for` to limit how long an amount, while or until policy can run.
Use `subscription.foreach` to process each consumed message.

## Custom Function Integration

Custom functions allow reusing integration patterns across workflows.

### Inline Custom Function

<code-block lang="yaml" src="workflows/lemline-examples-integrations/inline-custom-function.yaml"/>

This example demonstrates:
- Defining a custom function inline
- Adding input schema validation
- Reusing the function within the workflow

### Cataloged Function

Custom functions can also be defined in a separate catalog and referenced across multiple workflows:

<code-block lang="yaml" src="workflows/lemline-examples-integrations/cataloged-function.yaml"/>

This enables:
- Function reuse across multiple workflows
- Centralized integration pattern maintenance
- Consistent API access patterns

## Best Practices for Integrations

When implementing integration patterns in Lemline:

1. **Error handling**: Always implement error handling for external service calls using try-catch blocks
2. **Timeouts**: Set appropriate timeouts for service calls to prevent workflow hangs (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
3. **Retry strategies**: Use retry mechanisms with backoff for transient failures
4. **Authentication**: Securely manage credentials using secrets management
5. **Data validation**: Validate inputs and outputs using schemas
6. **Monitoring**: Add appropriate logging for external service interactions
7. **Idempotency**: Design integrations to be idempotent to handle retries safely

## Complex Integration Example

This example combines multiple integration patterns:

<code-block lang="yaml" src="workflows/lemline-examples-integrations/complex-integration-example.yaml"/>

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