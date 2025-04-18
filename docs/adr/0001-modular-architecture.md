# [ADR-0001] Modular Architecture

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires handling various aspects such as workflow definition, execution, persistence, and messaging. We needed to decide on an architectural approach that would allow for separation of concerns, maintainability, and extensibility.

## Decision

We have decided to adopt a modular architecture with the following main modules:

1. **lemline-core**: Implements the Serverless Workflow DSL itself, including workflow definitions, execution logic, and state management.
2. **lemline-worker**: Implements the runtime for the Serverless Workflow DSL, handling messaging, persistence, and integration with external systems.
3. **lemline-docs**: Contains the documentation for the Serverless Workflow DSL.
4. **lemline-common**: Contains shared utilities and common functionality used across other modules.

Each module has a clear responsibility and well-defined interfaces for interaction with other modules.

## Consequences

### Positive

- **Separation of concerns**: Each module has a specific responsibility, making the codebase easier to understand and maintain.
- **Independent development**: Teams can work on different modules in parallel with minimal interference.
- **Testability**: Modules can be tested independently, allowing for more focused and efficient testing.
- **Reusability**: Common functionality is extracted into shared modules, reducing duplication.
- **Extensibility**: New functionality can be added by extending existing modules or adding new ones without affecting the entire system.

### Negative

- **Increased complexity**: Managing dependencies between modules requires careful consideration.
- **Potential for over-modularization**: There's a risk of creating too many small modules, which could increase overhead.
- **Integration challenges**: Ensuring that all modules work together correctly requires thorough integration testing.

## Alternatives Considered

### Monolithic Architecture

A monolithic architecture would have all functionality in a single codebase without clear module boundaries. This approach was rejected because:
- It would make the codebase harder to understand and maintain as it grows.
- It would make it difficult for multiple teams to work on the project simultaneously.
- It would make testing more challenging, as it would be harder to isolate specific components.

### Microservices Architecture

A microservices architecture would have each component as a separate service with its own deployment and runtime. This approach was rejected because:
- It would introduce unnecessary operational complexity for the current scale of the project.
- It would increase latency due to network communication between services.
- It would make debugging and tracing more challenging.

## References

- [Serverless Workflow Specification](https://serverlessworkflow.io/)
- [Quarkus Architecture](https://quarkus.io/guides/architecture)