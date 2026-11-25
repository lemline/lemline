# Lemline Integration Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0
> authentication. Lemline registers them but does not execute them yet: such a task fails when it
> runs, with the error shown under each example. Write those calls with `call: http` and `basic` or `bearer`
> authentication, as in the HTTP examples, or exchange events with `emit` and `listen`.

## Run the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. Start the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

This document provides examples of integrating Lemline workflows with external services using various protocols and standards. These integration patterns enable workflows to interact with APIs, services, and messaging systems.

## HTTP/REST API Integration

HTTP/REST is one of the most common integration methods for connecting workflows to external services.

### Basic HTTP Call

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-http-call.yaml"/>

Save it as `basic-http-call.yaml`:

```bash
bin/lemline definition post -f basic-http-call.yaml
bin/lemline instance start examples call-http-shorthand-endpoint --input '{"productId":"SKU-3"}'
```

Expect the pruning shears: `{"id":"SKU-3","name":"Pruning shears","category":"garden","price":18,"available":10}`.

This example demonstrates:
- A simple HTTP GET request
- A URI built from the `productId` workflow input with a runtime expression

### HTTP with Query Parameters

<code-block lang="yaml" src="workflows/lemline-examples-integrations/http-with-query-parameters.yaml"/>

Save it as `http-with-query-parameters.yaml`:

```bash
bin/lemline definition post -f http-with-query-parameters.yaml
bin/lemline instance start examples http-query-params --input '{"searchQuery":"hose"}'
bin/lemline instance start examples http-query-params --input '{}'
```

The first instance returns one page with the garden hose (`items` lists `SKU-1`, `total` is 1). The second is refused by the input schema before any instance exists: `Missing required field(s): 'searchQuery'`.

This example shows:
- Using query parameters with the `query` property
- Dynamic query values using expressions
- Input schema validation

### HTTP with Authentication

<code-block lang="yaml" src="workflows/lemline-examples-integrations/http-with-authentication.yaml"/>

Lemline registers this definition but cannot run it: the instance faults with `Unsupported OAuth2 authentication`
before any request. See [Configuring OAuth 2.0 Authentication](lemline-howto-oauth2.md) to see it, and
[How to use API keys](lemline-howto-api-keys.md) for the methods that work: bearer token, basic authentication and
keys in headers or query parameters.

This example demonstrates:
- OAuth2 authentication for HTTP calls
- Client credentials grant type
- Custom token endpoints


## OpenAPI Integration

OpenAPI integration allows workflows to interact with services described by OpenAPI specifications.

### Basic OpenAPI Call

<code-block lang="yaml" src="workflows/lemline-examples-integrations/basic-openapi-call.yaml"/>

Save it as `basic-openapi-call.yaml`:

```bash
bin/lemline definition post -f basic-openapi-call.yaml
bin/lemline instance start examples openapi-list-products
```

The definition registers; the instance faults with `Unknown task type: CallOpenAPI`. [How to describe an OpenAPI call](lemline-howto-openapi.md) shows the same operation written as an HTTP call.

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

Save it as `basic-grpc-call.yaml`:

```bash
bin/lemline definition post -f basic-grpc-call.yaml
bin/lemline instance start examples grpc-get-stock --input '{"sku":"SKU-1"}'
```

The definition registers; the instance faults with `Unknown task type: CallGRPC`. The shop has no gRPC server.

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

Lemline has no AsyncAPI executor: the task fails with `Unknown task type: CallAsyncAPI`. To publish an event from a
workflow, use an [`emit` task](lemline-howto-events.md#emit-an-order-event).

This example demonstrates:
- Referencing an AsyncAPI specification
- Publishing a message to a specific server
- Specifying the message payload
- Including authentication

### Subscribing to Messages

<code-block lang="yaml" src="workflows/lemline-examples-integrations/subscribing-to-messages.yaml"/>

This one fails the same way. To wait for events, use a [`listen` task](lemline-howto-events.md#wait-for-a-correlated-event).

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

Save it as `inline-custom-function.yaml`:

```bash
bin/lemline definition post -f inline-custom-function.yaml
bin/lemline instance start examples call-custom-function-inline
```

The function validates its input, then calls the shop: expect the watering can, `{"id":"SKU-2","name":"Watering can","category":"garden","price":12,"available":10}`.

This example demonstrates:
- Defining a custom function inline
- Adding input schema validation
- Reusing the function within the workflow

### Cataloged Function

Custom functions can also be defined in a separate catalog and referenced across multiple workflows:

<code-block lang="yaml" src="workflows/lemline-examples-integrations/cataloged-function.yaml"/>

Save it as `cataloged-function.yaml`:

```bash
bin/lemline definition post -f cataloged-function.yaml
bin/lemline instance start examples call-custom-function-cataloged
```

A catalog function is called as `name:version@catalog`, with lowercase names. The catalog endpoint is the catalog root:
Lemline reads `get-product:1.0.0@shop-catalog` from
`http://127.0.0.1:8787/catalog/functions/get-product/1.0.0/function.yaml`, which the tutorial shop serves. That
definition is the same as the inline function: it validates `productId`, then calls the shop. Expect the watering can,
`{"id":"SKU-2","name":"Watering can","category":"garden","price":12,"available":10}`. Catalog endpoints can also be
`file://` URLs; an endpoint with authentication is not supported yet.

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

This example combines several integration patterns that Lemline runs:

<code-block lang="yaml" src="workflows/lemline-examples-integrations/complex-integration-example.yaml"/>

Save it as `complex-integration-example.yaml`:

```bash
bin/lemline definition post -f complex-integration-example.yaml
bin/lemline instance start examples complex-integration --input '{"orderId":"ORD-1101","customerId":"C-100","orderItems":[{"id":"SKU-1","quantity":1}],"paymentToken":"approved"}'
bin/lemline instance start examples complex-integration --input '{"orderId":"ORD-1102","customerId":"C-100","orderItems":[{"id":"SKU-1","quantity":1}],"paymentToken":"declined"}'
bin/lemline instance start examples complex-integration --input '{"orderId":"ORD-1103","customerId":"C-100","orderItems":[{"id":"SKU-1","quantity":500}],"paymentToken":"approved"}'
```

| Order | What happens | Where to see it |
|---|---|---|
| ORD-1101 | Stock reserved, payment approved, `com.example.shipping.requested` emitted with the customer name | The shop's reservations and charges |
| ORD-1102 | The charge answers HTTP 402; the error routes to `notifyCustomer` | A customer inbox entry for `ORD-1102` |
| ORD-1103 | The reservation answers HTTP 409; the error routes to `logError` | An audit entry for `ORD-1103` starting with `HTTP 409` |

All three instances complete. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

This complex example demonstrates:
- A reusable function declared under `use.functions`
- HTTP calls to several shop services
- An event emitted for the shipping service
- Error routing on the failure that occurred
- Data shared through `$context`

## Conclusion

These integration examples showcase Lemline's ability to connect with various external services and systems. By leveraging these patterns, you can build workflows that:

- Integrate with diverse APIs and services
- Process events and messages
- Reuse integration logic across workflows
- Handle errors and retries gracefully
- Ensure secure communication with external systems

For examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.