# API Integration Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 authentication. The Lemline runtime does not execute these yet: such a task fails when it runs. Write those calls with `call: http` and `basic` or `bearer` authentication to run them on Lemline.


This document provides practical examples of API integrations in Lemline workflows. It demonstrates different approaches to connecting with REST APIs, GraphQL, OpenAPI, gRPC, and other API types, covering authentication, error handling, and data transformation.

## REST API Integration Examples

### Basic REST API Workflow

This example demonstrates a simple workflow that interacts with multiple REST endpoints.

<code-block lang="yaml" src="workflows/lemline-examples-api/basic-rest-api-workflow.yaml"/>

### Pagination Handling

This example shows how to handle paginated API responses.

<code-block lang="yaml" src="workflows/lemline-examples-api/pagination-handling.yaml"/>

### Request Throttling

This example demonstrates how to implement API request throttling to respect rate limits.

<code-block lang="yaml" src="workflows/lemline-examples-api/request-throttling.yaml"/>

## OpenAPI Integration Examples

### OpenAPI Specification Integration

This example demonstrates how to use an OpenAPI specification for API integration.

<code-block lang="yaml" src="workflows/lemline-examples-api/openapi-specification-integration.yaml"/>

### OpenAPI with Authentication

This example shows OpenAPI integration with different authentication methods.

<code-block lang="yaml" src="workflows/lemline-examples-api/openapi-with-authentication.yaml"/>

## GraphQL Integration Examples

### Basic GraphQL Query

This example demonstrates a basic GraphQL query using HTTP integration.

<code-block lang="yaml" src="workflows/lemline-examples-api/basic-graphql-query.yaml"/>

### GraphQL Mutation

This example demonstrates a GraphQL mutation.

<code-block lang="yaml" src="workflows/lemline-examples-api/graphql-mutation.yaml"/>

## gRPC Integration Examples

### Basic gRPC Service Call

This example demonstrates integrating with a gRPC service.

<code-block lang="yaml" src="workflows/lemline-examples-api/basic-grpc-service-call.yaml"/>

### gRPC with Authentication

This example demonstrates a gRPC call authenticated with OAuth2 client credentials.

<code-block lang="yaml" src="workflows/dsl-call-grpc/example-using-authentication.yaml"/>

## AsyncAPI Integration Examples

### Event-Driven API Integration

This example demonstrates AsyncAPI integration for event-driven architectures.

<code-block lang="yaml" src="workflows/lemline-examples-api/event-driven-api-integration.yaml"/>

### Message Streaming with AsyncAPI

This example shows how to process a stream of messages with AsyncAPI: each sensor reading is sent to an analysis service, and an alert is published when the analysis asks for one. A failed call is retried up to three times.

<code-block lang="yaml" src="workflows/lemline-examples-api/message-streaming-with-asyncapi.yaml"/>

## API Integration Best Practices

When implementing API integrations in Lemline workflows, consider these best practices:

1. **Authentication Management**: Use secrets for managing API credentials securely
2. **Request Validation**: Validate input data before making API calls
3. **Response Validation**: Validate API responses against expected schemas
4. **Error Handling**: Implement robust error handling with appropriate retry policies
5. **Rate Limiting**: Respect API rate limits by implementing throttling or backoff strategies
6. **Pagination**: Handle paginated API responses properly
7. **Idempotency**: Design workflows to handle API call retries safely
8. **Circuit Breaking**: Implement circuit breaking for failing endpoints
9. **Monitoring**: Include appropriate logging for API interactions
10. **Timeout Handling**: Set appropriate timeouts for API calls (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)

## Conclusion

These examples demonstrate how to implement various API integration patterns in Lemline workflows. By leveraging the Open Workflow DSL's capabilities, you can build robust API integrations that handle authentication, error conditions, pagination, and other common challenges.

Whether you're working with REST APIs, GraphQL, OpenAPI specifications, gRPC services, or event-driven AsyncAPI definitions, Lemline provides a consistent approach to API integration that emphasizes reliability, security, and maintainability.

For more examples of error handling in API integrations, see the [Error Handling Examples](lemline-examples-error-handling.md) document.
