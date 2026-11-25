# Lemline Examples Library

This document provides an overview of the examples available in the Lemline project, organized by category and use case. Each example demonstrates key features of the Open Workflow DSL as implemented by Lemline.

## Run the tutorial shop

Start with [an order acknowledgement](lemline-tutorial-hello.md), then [reserve, charge and ship an order](lemline-tutorial-order-processing.md). The same shop displays the customer confirmation and the [warehouse manager's temperature alerts](lemline-tutorial-iot.md). Each tutorial includes its inputs and expected results.

## Run the Examples

Each example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its
worker running. Run the commands from a second terminal in the setup directory. With the JAR, substitute
`java -jar lemline.jar` for `bin/lemline`. Start the tutorial shop from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The event example also needs the tutorial publisher. Save it as `tutorial-publisher.yaml` and register it:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

## Introduction

The examples in this library showcase how to implement various workflow patterns using the Open Workflow DSL. These examples range from simple, focused demonstrations of specific features to complex, real-world scenarios that combine multiple features.

Each example follows a consistent pattern:
- **Document metadata**: Defines the DSL version, namespace, workflow name, and version
- **Workflow structure**: Shows the actual workflow definition with tasks, control flow, error handling, etc.
- **Sample input/output**: Where applicable, includes sample input data and expected output

## Example Categories

The examples are organized into the following categories:

1. [Basic Workflow Patterns](#basic-workflow-patterns)
2. [External Service Integration](#external-service-integration)
3. [Error Handling and Resilience](#error-handling-and-resilience)
4. [Data Transformation](#data-transformation)
5. [Event Processing](#event-processing)
6. [Real-world Examples](#real-world-examples)

## Basic Workflow Patterns

Basic workflow patterns demonstrate the fundamental building blocks of Open Workflow, such as:

- Sequential task execution
- Conditional logic with `switch` statements
- Loops with `for` and `do` constructs
- Nested task execution
- Variable setting with `set`

Read the available stock in a nested task and pass the result to its parent:

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/nested-tasks-example.yaml"/>

Save it as `nested-tasks-example.yaml`:

```bash
bin/lemline definition post -f nested-tasks-example.yaml
bin/lemline instance start examples stock-summary
```

The inner list reads the stock and passes it to its parent: expect `{"message":"SKU-1 available: 100"}` on a fresh shop.

For more basic patterns, see the [Basic Patterns Examples](lemline-examples-basic-patterns.md) document.

## External Service Integration

These examples demonstrate how to integrate with external services through various protocols:

- HTTP/REST API calls
- OpenAPI integration
- gRPC service calls
- AsyncAPI for message-based communication
- Custom function execution

This example chains two HTTP calls: it searches the catalog, then reads the product it found:

<code-block lang="yaml" src="workflows/lemline-examples-library/external-service-integration.yaml"/>

Save it as `external-service-integration.yaml`:

```bash
bin/lemline definition post -f external-service-integration.yaml
bin/lemline instance start examples product-lookup --input '{"search":"lamp"}'
```

The first call returns the full response; the workflow exports the ID found in its `content`, then reads that product: expect the desk lamp, `{"id":"SKU-4","name":"Desk lamp","category":"home","price":30,"available":10}`.

For more integration examples, see the [Integration Examples](lemline-examples-integrations.md) document.

## Error Handling and Resilience

These examples show how to implement robust error handling and resilience patterns:

- Try-catch blocks for error handling
- Retry mechanisms with backoff strategies
- Error type classifications
- Compensating transactions

This example retries a failing call with exponential backoff:

<code-block lang="yaml" src="workflows/lemline-examples-library/error-handling-and-resilience.yaml"/>

Save it as `error-handling-and-resilience.yaml`:

```bash
bin/lemline definition post -f error-handling-and-resilience.yaml
bin/lemline instance start examples stock-with-retry --input '{"failures":2}'
```

The shop answers HTTP 503 twice; the catch matches the `communication` error that a failed HTTP call raises, retries after about 1 and 2 seconds, and the workflow returns the stock. Allow about 30 seconds: the worker picks up retries every 10 seconds.

For more error handling examples, see the [Error Handling Examples](lemline-examples-error-handling.md) document.

## Data Transformation

These examples demonstrate data manipulation and transformation techniques:

- JQ expressions for JSON transformation
- Variable setting and manipulation
- Data mapping between tasks
- Complex transformation patterns

This example sets a value and transforms it in the next task:

<code-block lang="yaml" src="workflows/lemline-examples-library/data-transformation.yaml"/>

Save it as `data-transformation.yaml`:

```bash
bin/lemline definition post -f data-transformation.yaml
bin/lemline instance start examples transform-user
```

Expect `{"transformedUser":{"name":"Ada Lovelace","role":"admin","lastAccess":"<current time>"}}`, with the time as an ISO 8601 string.

## Event Processing

These examples show how to handle events in workflows:

- Event listening patterns
- Event correlation
- Event-driven workflows
- Message consumption strategies

This example waits for the first of two event types:

<code-block lang="yaml" src="workflows/lemline-examples-library/event-processing.yaml"/>

Save it as `event-processing.yaml`:

```bash
bin/lemline definition post -f event-processing.yaml
bin/lemline instance start examples listen-to-any-example
bin/lemline instance start examples listen-to-any-example
```

Both instances wait. Publish one order event:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.cancelled","data":{"orderId":"ORD-1003"}}'
```

The event resumes both listeners: each completes with `[{"orderId":"ORD-1003"}]`. A listener that receives no event
within 30 seconds faults with a `timeout` error 30 to 40 seconds after it started.

## Real-world Examples

These examples combine multiple features to demonstrate realistic use cases:

- Order processing workflows
- Multi-step approval processes
- Data enrichment pipelines
- Notification systems

This smaller example routes a digital or physical order to a different fulfillment method:

<code-block lang="yaml" src="workflows/lemline-examples-library/real-world-examples.yaml"/>

Save it as `real-world-examples.yaml`:

```bash
bin/lemline definition post -f real-world-examples.yaml
bin/lemline instance start examples order-fulfillment --input '{"itemType":"digital"}'
bin/lemline instance start examples order-fulfillment --input '{"itemType":"physical"}'
```

Expect `{"fulfillment":"digital-delivery"}`, then `{"fulfillment":"physical-shipping"}`.

For more complex examples, see the [Real-world Examples](lemline-examples-real-world.md) document.

## Running the Examples

The general form of the commands used above is:

```text
bin/lemline definition post -f <file.yaml>
bin/lemline instance start <namespace> <name> [<version>] --input '<json>'
```

The worker started with `bin/lemline listen` runs the instance. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Conclusion

These examples demonstrate the flexibility and power of the Open Workflow DSL as implemented by Lemline. Use them as a reference when building your own workflows, adapting and combining patterns to suit your specific use cases.

For more detailed explanations of specific patterns, refer to the category-specific documents linked above.