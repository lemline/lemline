# Lemline Unified Logging Strategy

This document outlines the unified logging strategy for the Lemline project, providing guidelines for consistent logging across all modules.

## Overview

The Lemline project uses a unified logging approach based on SLF4J with Quarkus configuration. The logging strategy is designed to:

1. Provide consistent log formats across all modules
2. Include contextual information in logs for better traceability
3. Use appropriate log levels for different types of events
4. Support structured logging for better analysis in production environments

## Log Levels

The following log levels are used throughout the application:

| Level | Purpose | Example Usage |
|-------|---------|---------------|
| TRACE | Very detailed information, useful for debugging complex issues | Detailed method entry/exit, variable values |
| DEBUG | Detailed information on the flow through the system | Method execution, decision points |
| INFO | Interesting runtime events | Workflow started/completed, service startup |
| WARN | Potentially harmful situations that might lead to errors | Caught exceptions, retries |
| ERROR | Error events that might still allow the application to continue running | Uncaught exceptions, service failures |

## Contextual Logging

The logging utility provides context-aware logging through MDC (Mapped Diagnostic Context). This allows including contextual information in all log messages without explicitly adding it to each log statement.

### Standard Context Keys

The following standard context keys are defined in `LogContext`:

- `workflowId`: The workflow instance ID
- `workflowName`: The workflow name
- `workflowVersion`: The workflow version
- `nodePosition`: The current node position
- `correlationId`: A correlation ID for tracing requests across services
- `requestId`: A unique ID for each request
- `userId`: The ID of the user making the request (if applicable)

### Using Context-Aware Logging

To use context-aware logging, wrap your code with the `withLoggingContext` or `withWorkflowContext` functions:

```kotlin
// General context
withLoggingContext(
    LogContext.REQUEST_ID to requestId,
    LogContext.CORRELATION_ID to correlationId
) {
    // Your code here
    logger.info { "Processing request" }
}

// Workflow-specific context
withWorkflowContext(
    workflowId = id,
    workflowName = name,
    workflowVersion = version,
    nodePosition = position.toString()
) {
    // Your code here
    logger.info { "Processing workflow" }
}
```

## Log Format

The log format is configured in `application.properties` and includes:

- Timestamp with milliseconds
- Log level
- Context information (requestId, workflowId, correlationId)
- Logger name (class name)
- Thread name
- Message
- Exception (if present)

Example:
```
2023-06-15 10:15:30.123 INFO [req123,wf456,corr789] [WorkflowConsumer] (main) Processing workflow message
```

## JSON Logging for Production

For production environments, JSON logging can be enabled for better log analysis. This is configured in `application.properties` but commented out by default:

```properties
# JSON logging for production environments
#quarkus.log.handler.json=true
#quarkus.log.handler.json.date-format=yyyy-MM-dd HH:mm:ss.SSS
#quarkus.log.handler.json.pretty-print=false
#quarkus.log.handler.json.record-delimiter=\n
#quarkus.log.handler.json.exception-output-type=formatted
#quarkus.log.handler.json.additional-field.app-name.value=${quarkus.application.name}
#quarkus.log.handler.json.additional-field.env.value=production
```

## Best Practices

1. **Use the appropriate log level**: Choose the log level based on the importance and audience of the message.
2. **Use lambda for log messages**: Always use the lambda syntax for log messages to avoid string concatenation when the log level is disabled.
   ```kotlin
   // Good
   logger.debug { "Processing item $itemId" }
   
   // Bad
   logger.debug("Processing item " + itemId)
   ```
3. **Include context information**: Use the context-aware logging functions to include contextual information in logs.
4. **Be concise but informative**: Log messages should be concise but provide enough information to understand what's happening.
5. **Log at entry and exit points**: Log at the beginning and end of important methods to track execution flow.
6. **Include relevant data**: Include relevant data in log messages, but be careful not to log sensitive information.
7. **Use structured logging in production**: Enable JSON logging in production environments for better log analysis.

## Configuration

The logging configuration is defined in `application.properties` files:

- Main configuration: `/lemline-worker/src/main/resources/application.properties`
- Test configuration: `/lemline-worker/src/test/resources/application.properties`

These files define the log levels, format, and other logging-related settings.

## Implementation

The logging utility is implemented in `/lemline-common/src/main/kotlin/com/lemline/common/logger.kt` and provides:

- Extension functions for getting a logger for any class
- Extension functions for logging at different levels (TRACE, DEBUG, INFO, WARN, ERROR)
- Functions for setting context values for logging

## Examples

### Basic Logging

```kotlin
private val logger = logger()

fun someMethod() {
    logger.info { "Starting method execution" }
    // Method implementation
    logger.debug { "Processed item: $item" }
    // More implementation
    logger.info { "Method execution completed" }
}
```

### Context-Aware Logging

```kotlin
private val logger = logger()

suspend fun run() {
    withWorkflowContext(
        workflowId = id,
        workflowName = name,
        workflowVersion = version,
        nodePosition = position.toString()
    ) {
        logger.info { "Starting workflow execution" }
        // Workflow execution
        logger.info { "Workflow execution completed" }
    }
}
```

### Error Logging

```kotlin
try {
    // Some code that might throw an exception
} catch (e: Exception) {
    logger.error(e) { "Failed to process item: $itemId" }
    // Error handling
}
```