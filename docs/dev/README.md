# Lemline Developer Documentation

Welcome to the Lemline developer documentation. This guide explains the implementation details of the Lemline project, a modern runtime for the Serverless Workflow DSL.

## Core Architecture

Lemline is structured with a modular architecture:

- **lemline-core**: Implements the Serverless Workflow DSL itself
- **lemline-runner**: Implements the runtime with messaging, persistence, and external integrations
- **lemline-docs**: Contains documentation

## Technical Guides

### Core Implementation

- [Workflow Execution Model](workflow-execution.md) - How workflows are represented and executed
- [Error Handling](error-handling.md) - Exception framework with retry and compensation

### Runtime Services

- [Messaging Architecture](messaging.md) - Event-driven communication with SmallRye Reactive Messaging
- [Database Storage Strategy](database.md) - Persistence layer with native SQL
- [Configuration System](configuration.md) - Flexible, type-safe configuration
- [Logging Strategy](logging.md) - Contextual logging with MDC

## Extension Guides

Each implementation guide includes a section on how to extend that component:

- **Adding New Node Types** - Extend the workflow execution with custom tasks
- **Adding New Messaging Technology** - Integrate with additional message brokers
- **Adding New Database Type** - Support alternative database backends
- **Implementing Custom Error Handling** - Create specialized error handling flows

## Development Workflow

### Getting Started

1. Clone the repository
2. Build the project: `./gradlew build`
3. Run the tests: `./gradlew test`
4. Start the service: `./gradlew quarkusDev`

### Development Best Practices

- Add tests for all new functionality
- Follow the Kotlin coding conventions
- Use appropriate logging levels and include context
- Document extension points and configuration options
- Ensure backward compatibility when possible

## Debugging and Troubleshooting

Each component documentation includes a troubleshooting section with:

- Common issues and their solutions
- Debugging tips and techniques
- How to enable detailed logging
- Performance considerations

## Reference Documentation

- [Serverless Workflow Specification](https://serverlessworkflow.io/)
- [Quarkus Documentation](https://quarkus.io/guides/)
- [SmallRye Reactive Messaging](https://smallrye.io/smallrye-reactive-messaging/)
- [Kotlin Language Reference](https://kotlinlang.org/docs/reference/) 