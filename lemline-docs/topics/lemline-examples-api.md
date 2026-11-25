# API Integration Examples

> **Runtime support:** the OpenAPI, gRPC and AsyncAPI examples on this page use `call: openapi`, `call: grpc` and
> `call: asyncapi`, and one of them OAuth 2.0 authentication. Lemline registers them but does not execute them yet: such
> a task fails when it runs, with `Unknown task type: CallOpenAPI`, `CallGRPC` or `CallAsyncAPI`. The REST and GraphQL
> examples run against the tutorial shop.

This document provides practical examples of API integrations in Lemline workflows. It demonstrates different approaches to connecting with REST APIs, GraphQL, OpenAPI, gRPC, and other API types, covering authentication, error handling, and data transformation.

## Run the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and run the commands from the setup directory. With the JAR,
substitute `java -jar lemline.jar` for `bin/lemline`. Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop)
from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The examples read their API keys and tokens from secrets. The shop accepts any value on these routes and lists the
headers it received under **Requests**. Stop the setup worker with Ctrl+C, export demo values, and start it again:

```bash
export API_KEY='demo-api-key'
export EMAIL_SERVICE_TOKEN='demo-token'
export API_TOKEN='demo-token'
bin/lemline listen --debug
```

Run the other commands from a second terminal in the setup directory.

## REST API Integration Examples

### Basic REST API Workflow

This example demonstrates a simple workflow that interacts with multiple REST endpoints.

<code-block lang="yaml" src="workflows/lemline-examples-api/basic-rest-api-workflow.yaml"/>

Save it as `basic-rest-api-workflow.yaml`:

```bash
bin/lemline definition post -f basic-rest-api-workflow.yaml
bin/lemline instance start examples register-customer --input '{"email":"katherine@example.com","name":"Katherine Johnson"}'
bin/lemline instance start examples register-customer --input '{"email":"katherine@localhost","name":"Katherine Johnson"}'
```

The first email is valid for the shop: the customer is created as `C-901` and a `Welcome, Katherine Johnson` message reaches their inbox. The workflow input schema accepts `katherine@localhost`, but the shop's check requires a domain with a dot: the second instance completes with `{"result":{"success":false,"reason":"Invalid email address","email":"katherine@localhost"}}`.

### Pagination Handling

This example shows how to handle paginated API responses.

<code-block lang="yaml" src="workflows/lemline-examples-api/pagination-handling.yaml"/>

Save it as `pagination-handling.yaml`:

```bash
bin/lemline definition post -f pagination-handling.yaml
bin/lemline instance start examples pagination-handling
```

The shop returns three products per page. The loop reads pages 1 to 3 and stops when `hasMore` is false: expect `totalProducts` 7 and the seven IDs from `SKU-1` to `SKU-7`.

### Request Throttling

This example demonstrates how to implement API request throttling to respect rate limits.

<code-block lang="yaml" src="workflows/lemline-examples-api/request-throttling.yaml"/>

Save it as `request-throttling.yaml`:

```bash
bin/lemline definition post -f request-throttling.yaml
bin/lemline instance start examples request-throttling --input '{"items":["SKU-1","SKU-2","SKU-3","SKU-4","SKU-5","SKU-404"]}'
```

The items are processed in batches of five, with a 2-second pause after each batch. The shop answers HTTP 429 once for `SKU-2`: that call is retried after 10 seconds and succeeds. `SKU-404` gets HTTP 404, which is not retried and is recorded as a failure. Expect `succeeded: 5` and `failed: 1`. Allow about a minute: the worker picks up retries every 10 seconds.

## OpenAPI Integration Examples

### OpenAPI Specification Integration

This example demonstrates how to use an OpenAPI specification for API integration.

<code-block lang="yaml" src="workflows/lemline-examples-api/openapi-specification-integration.yaml"/>

Save it as `openapi-specification-integration.yaml`:

```bash
bin/lemline definition post -f openapi-specification-integration.yaml
bin/lemline instance start examples catalog-openapi
```

The definition targets the shop's OpenAPI document; the instance faults with `Unknown task type: CallOpenAPI`.

### OpenAPI with Authentication

This example shows OpenAPI integration with different authentication methods.

<code-block lang="yaml" src="workflows/lemline-examples-api/openapi-with-authentication.yaml"/>

Lemline cannot run this one either: besides the OpenAPI call, it has no OAuth 2.0 support.

## GraphQL Integration Examples

### Basic GraphQL Query

This example demonstrates a basic GraphQL query using HTTP integration.

<code-block lang="yaml" src="workflows/lemline-examples-api/basic-graphql-query.yaml"/>

Save it as `basic-graphql-query.yaml`:

```bash
bin/lemline definition post -f basic-graphql-query.yaml
bin/lemline instance start examples customer-graphql-query --input '{"customerId":"C-100"}'
```

The shop's GraphQL stand-in answers the `customer` query: expect `{"customerName":"Ada Lovelace","totalReviews":2,"orderCount":0}`.

### GraphQL Mutation

This example demonstrates a GraphQL mutation.

<code-block lang="yaml" src="workflows/lemline-examples-api/graphql-mutation.yaml"/>

Save it as `graphql-mutation.yaml`:

```bash
bin/lemline definition post -f graphql-mutation.yaml
bin/lemline instance start examples add-review-mutation --input '{"review":{"customerId":"C-200","productId":"SKU-2","rating":4,"title":"Good can"}}'
bin/lemline instance start examples add-review-mutation --input '{"review":{"customerId":"C-200","productId":"SKU-404","rating":9}}'
```

The first instance returns `{"result":{"success":true,"review":{"id":"R-3","rating":4,"title":"Good can"}}}`. The second gets GraphQL errors for the unknown product and the rating: `{"result":{"success":false,"errors":["Unknown product SKU-404","Rating must be from 1 to 5"]}}`.

## gRPC Integration Examples

### Basic gRPC Service Call

This example demonstrates integrating with a gRPC service.

<code-block lang="yaml" src="workflows/lemline-examples-api/basic-grpc-service-call.yaml"/>

Lemline registers this definition; an instance faults with `Unknown task type: CallGRPC`. See
[How to describe a gRPC call](lemline-howto-grpc.md) for the HTTP equivalent on the shop.

### gRPC with Authentication

This example demonstrates a gRPC call authenticated with OAuth2 client credentials.

<code-block lang="yaml" src="workflows/dsl-call-grpc/example-using-authentication.yaml"/>

This one fails the same way.

## AsyncAPI Integration Examples

### Event-Driven API Integration

This example demonstrates AsyncAPI integration for event-driven architectures.

<code-block lang="yaml" src="workflows/lemline-examples-api/event-driven-api-integration.yaml"/>

Lemline has no AsyncAPI executor: the task fails with `Unknown task type: CallAsyncAPI`. Use `emit` and `listen`, as in
[How to emit and listen for events](lemline-howto-events.md), to exchange events on Lemline's own channel.

### Message Streaming with AsyncAPI

This example shows how to process a stream of messages with AsyncAPI: each sensor reading is sent to an analysis service, and an alert is published when the analysis asks for one. A failed call is retried up to three times.

<code-block lang="yaml" src="workflows/lemline-examples-api/message-streaming-with-asyncapi.yaml"/>

This one fails the same way. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

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
