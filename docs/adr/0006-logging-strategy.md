# [ADR-0006] Logging Strategy

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires a comprehensive logging strategy to facilitate debugging, monitoring, and troubleshooting. We needed to decide on a logging approach that would be consistent, informative, and aligned with the project's requirements.

## Decision

We have decided to implement a unified logging strategy based on SLF4J with Quarkus configuration with the following characteristics:

1. **Consistent Log Formats**: We use a consistent log format across all modules, including timestamp, log level, context information, logger name, thread name, message, and exception (if present).

2. **Contextual Logging**: We implement context-aware logging through MDC (Mapped Diagnostic Context) to include contextual information in all log messages without explicitly adding it to each log statement.

3. **Standard Context Keys**: We define standard context keys such as `workflowId`, `workflowName`, `workflowVersion`, `nodePosition`, `correlationId`, `requestId`, and `userId`.

4. **Appropriate Log Levels**: We use appropriate log levels (TRACE, DEBUG, INFO, WARN, ERROR) for different types of events, with clear guidelines for when to use each level.

5. **Structured Logging**: We support structured logging in JSON format for better analysis in production environments.

6. **Lambda-based Logging**: We use lambda syntax for log messages to avoid string concatenation when the log level is disabled.

7. **Configuration Management**: We manage logging configuration in `application.properties` files, with separate configurations for main and test environments.

## Consequences

### Positive

- **Consistency**: A unified logging approach ensures consistency across all modules, making logs easier to read and analyze.
- **Traceability**: Contextual logging improves traceability by including relevant context information in all log messages.
- **Efficiency**: Lambda-based logging and appropriate log levels ensure efficient logging without unnecessary overhead.
- **Analyzability**: Structured logging in JSON format makes logs easier to analyze in production environments.
- **Flexibility**: Configurable logging allows for different logging behaviors in different environments.

### Negative

- **Learning Curve**: Developers need to understand the logging approach to effectively use it in their code.
- **Overhead**: Contextual logging adds some overhead to log message creation.
- **Configuration Complexity**: Managing logging configuration across different environments adds complexity.

## Alternatives Considered

### Ad-hoc Logging

An ad-hoc approach where each module or component defines its own logging format and approach was considered. This approach was rejected because:
- It would lead to inconsistent logs across the system, making them harder to read and analyze.
- It would make it harder to include contextual information in logs.
- It would likely result in less efficient logging practices.
- It would make it harder to configure logging behavior across the system.

### Custom Logging Framework

Implementing a custom logging framework instead of using SLF4J with Quarkus configuration was considered. This approach was rejected because:
- It would require significant development effort to implement features that are already provided by SLF4J and Quarkus.
- It would likely result in a less robust and well-tested solution.
- It would not leverage the integration with Quarkus and other components that SLF4J provides.
- It would introduce a learning curve for developers who are already familiar with SLF4J.

## References

- [SLF4J Documentation](https://www.slf4j.org/manual.html)
- [Quarkus Logging Guide](https://quarkus.io/guides/logging)
- [Mapped Diagnostic Context (MDC)](https://logback.qos.ch/manual/mdc.html)