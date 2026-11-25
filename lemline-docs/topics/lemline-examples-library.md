# Lemline Examples Library

This document provides an overview of the examples available in the Lemline project, organized by category and use case. Each example demonstrates key features of the Open Workflow DSL as implemented by Lemline.

## Run the tutorial shop

Start with [an order acknowledgement](lemline-tutorial-hello.md), then [reserve, charge and ship an order](lemline-tutorial-order-processing.md). The same shop displays the customer confirmation and the [warehouse manager's temperature alerts](lemline-tutorial-iot.md). Each tutorial includes its inputs and expected results.

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

For more basic patterns, see the [Basic Patterns Examples](lemline-examples-basic-patterns.md) document.

## External Service Integration

These examples demonstrate how to integrate with external services through various protocols:

- HTTP/REST API calls
- OpenAPI integration
- gRPC service calls
- AsyncAPI for message-based communication
- Custom function execution

Example: `star-wars-homeworld.yaml` - Shows HTTP endpoint calls with dynamic parameters:

<code-block lang="yaml" src="workflows/lemline-examples-library/external-service-integration.yaml"/>

For more integration examples, see the [Integration Examples](lemline-examples-integrations.md) document.

## Error Handling and Resilience

These examples show how to implement robust error handling and resilience patterns:

- Try-catch blocks for error handling
- Retry mechanisms with backoff strategies
- Error type classifications
- Compensating transactions

Example: `try-catch-retry-inline.yaml` - Shows error handling with retry logic:

<code-block lang="yaml" src="workflows/lemline-examples-library/error-handling-and-resilience.yaml"/>

For more error handling examples, see the [Error Handling Examples](lemline-examples-error-handling.md) document.

## Data Transformation

These examples demonstrate data manipulation and transformation techniques:

- JQ expressions for JSON transformation
- Variable setting and manipulation
- Data mapping between tasks
- Complex transformation patterns

Example: `set.yaml` - Shows how to set and transform variables:

<code-block lang="yaml" src="workflows/lemline-examples-library/data-transformation.yaml"/>

## Event Processing

These examples show how to handle events in workflows:

- Event listening patterns
- Event correlation
- Event-driven workflows
- Message consumption strategies

Example: `listen-to-any.yaml` - Shows how to listen for events:

<code-block lang="yaml" src="workflows/lemline-examples-library/event-processing.yaml"/>

## Real-world Examples

These examples combine multiple features to demonstrate realistic use cases:

- Order processing workflows
- Multi-step approval processes
- Data enrichment pipelines
- Notification systems

This smaller example routes a digital or physical order to a different fulfillment method:

<code-block lang="yaml" src="workflows/lemline-examples-library/real-world-examples.yaml"/>

For more complex examples, see the [Real-world Examples](lemline-examples-real-world.md) document.

## Running the Examples

To run any of these examples using the Lemline CLI:

```bash
# Install workflow definition
lemline definition post -f path/to/example.yaml

# Start workflow instance (a runner started with `lemline listen` must be running)
lemline instance start <namespace> <name> [<version>]
```

For examples that require input, provide JSON input:

```bash
lemline instance start <namespace> <name> <version> -i '{"key": "value"}'
```

## Conclusion

These examples demonstrate the flexibility and power of the Open Workflow DSL as implemented by Lemline. Use them as a reference when building your own workflows, adapting and combining patterns to suit your specific use cases.

For more detailed explanations of specific patterns, refer to the category-specific documents linked above.