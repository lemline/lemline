# Lemline Examples Library

This document provides an overview of the examples available in the Lemline project, organized by category and use case. Each example demonstrates key features of the Serverless Workflow DSL as implemented by Lemline.

## Introduction

The examples in this library showcase how to implement various workflow patterns using the Serverless Workflow DSL. These examples range from simple, focused demonstrations of specific features to complex, real-world scenarios that combine multiple features.

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

Basic workflow patterns demonstrate the fundamental building blocks of Serverless Workflow, such as:

- Sequential task execution
- Conditional logic with `switch` statements
- Loops with `for` and `do` constructs
- Nested task execution
- Variable setting with `set`

Example: `do-nested.yaml` - Demonstrates nested task execution with multiple HTTP calls and data manipulation:

```yaml
document:
  dsl: '1.0.0-alpha5'
  namespace: examples
  name: call-http-shorthand-endpoint
  version: '0.1.0'
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
  - nested:
      do:
        - init:
            set:
              startEvent: ${ $workflow.input[0] }
        - getPet:
            call: http
            with:
              method: get
              endpoint: https://petstore.swagger.io/v2/pet/{petId}
        # Additional nested tasks...
```

For more basic patterns, see the [Basic Patterns Examples](lemline-examples-basic-patterns.md) document.

## External Service Integration

These examples demonstrate how to integrate with external services through various protocols:

- HTTP/REST API calls
- OpenAPI integration
- gRPC service calls
- AsyncAPI for message-based communication
- Custom function execution

Example: `star-wars-homeworld.yaml` - Shows HTTP endpoint calls with dynamic parameters:

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: star-wars-homeplanet
  version: 1.0.0
do:
  - getStarWarsCharacter:
      call: http
      with:
        method: get
        endpoint: https://swapi.dev/api/people/{id}
        output: response
      export:
        as:
          homeworld: ${ .content.homeworld }
  - getStarWarsHomeworld:
      call: http
      with:
        method: get
        endpoint: ${ $context.homeworld }
```

For more integration examples, see the [Integration Examples](lemline-examples-integrations.md) document.

## Error Handling and Resilience

These examples show how to implement robust error handling and resilience patterns:

- Try-catch blocks for error handling
- Retry mechanisms with backoff strategies
- Error type classifications
- Compensating transactions

Example: `try-catch-retry-inline.yaml` - Shows error handling with retry logic:

```yaml
document:
  dsl: 1.0.0-alpha2
  namespace: examples
  name: star-wars-planets-batch
  version: 1.0.0-alpha2
do:
  - tryGetAllCharacters:
      try:
        - getAllCharacters:
            call: http
            with:
              method: get
              endpoint: https://swapi.dev/api/people/
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        retry:
          delay:
            seconds: 1
          backoff:
            exponential: { }
          limit:
            attempt:
              count: 3
```

For more error handling examples, see the [Error Handling Examples](lemline-examples-error-handling.md) document.

## Data Transformation

These examples demonstrate data manipulation and transformation techniques:

- JQ expressions for JSON transformation
- Variable setting and manipulation
- Data mapping between tasks
- Complex transformation patterns

Example: `set.yaml` - Shows how to set and transform variables:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: set-example
  version: '0.1.0'
do:
  - initData:
      set:
        user:
          name: "John Doe"
          role: "admin"
  - transformData:
      set:
        transformedUser: ${ .user + { lastAccess: now() } }
```

## Event Processing

These examples show how to handle events in workflows:

- Event listening patterns
- Event correlation
- Event-driven workflows
- Message consumption strategies

Example: `listen-to-any.yaml` - Shows how to listen for events:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: listen-to-any-example
  version: '0.1.0'
do:
  - waitForEvent:
      listen:
        to:
          any:
            - event: order.created
            - event: order.cancelled
        for:
          seconds: 30
```

## Real-world Examples

These examples combine multiple features to demonstrate realistic use cases:

- Order processing workflows
- Multi-step approval processes
- Data enrichment pipelines
- Notification systems

Example: `presentation.yaml` - A complex order fulfillment workflow:

```yaml
document:
  dsl: 1.0.0-alpha2
  namespace: examples
  name: order-fulfillment
  version: 1.0.0-alpha2

do:
  - checkItemType:
      switch:
        - ifDigital:
            when: .itemType == "digital"
            then: processDigitalOrder
        - default:
            then: checkPhysicalOrderLogic
  
  # Additional steps for different order types...
```

For more complex examples, see the [Real-world Examples](lemline-examples-real-world.md) document.

## Running the Examples

To run any of these examples using the Lemline CLI:

```bash
# Install workflow definition
lemline definition post -f path/to/example.yaml

# Start workflow instance
lemline instance start -n namespace.workflow-name -v version
```

For examples that require input, provide JSON input:

```bash
lemline instance start -n namespace.workflow-name -v version -i '{"key": "value"}'
```

## Conclusion

These examples demonstrate the flexibility and power of the Serverless Workflow DSL as implemented by Lemline. Use them as a reference when building your own workflows, adapting and combining patterns to suit your specific use cases.

For more detailed explanations of specific patterns, refer to the category-specific documents linked above.