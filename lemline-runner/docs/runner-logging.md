# Lemline Runner - Logging Strategy

This document covers the logging strategy for the lemline-runner module.

## Overview

Lemline uses SLF4J via kotlin-logging with Quarkus configuration. The logging system provides:

- Consistent log formats across all modules
- Contextual information through MDC (Mapped Diagnostic Context)
- Appropriate log levels for different event types
- Structured logging in JSON format for production
- Efficient lambda-based log messages

**Key dependency:** `io.github.oshai:kotlin-logging`

---

## Basic Logging

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

// Different log levels
logger.trace { "Very detailed information for debugging" }
logger.debug { "Debugging information" }
logger.info { "General information about system operation" }
logger.warn { "Warning about potential issues" }
logger.error { "Error that doesn't prevent the system from running" }
logger.error(exception) { "Error with exception details" }
```

---

## Log Levels

| Level | Use Case | Example |
|-------|----------|---------|
| TRACE | Detailed execution flow | "Entering method x with parameters a=1, b=2" |
| DEBUG | Information useful for debugging | "HTTP request sent to endpoint x" |
| INFO | Normal operational events | "Workflow 'order-processing' started" |
| WARN | Potential issues | "Database connection pool approaching limit" |
| ERROR | Runtime errors | "Failed to process HTTP request" |

---

## Contextual Logging with MDC

MDC (Mapped Diagnostic Context) adds context information to logs automatically.

### Setting Context

```kotlin
import org.slf4j.MDC

// Set context for the current thread
MDC.put("workflowId", workflowId.toString())
MDC.put("nodePosition", nodePosition.toString())

try {
    logger.info { "Executing workflow node" }
} finally {
    MDC.remove("workflowId")
    MDC.remove("nodePosition")
}
```

### Using Scope Function

```kotlin
import io.github.oshai.kotlinlogging.withLoggingContext

withLoggingContext(
    "workflowId" to workflowId.toString(),
    "nodePosition" to nodePosition.toString()
) {
    logger.info { "Executing workflow node" }
}
```

### Standard Context Keys

| Context Key | Description | Example Value |
|-------------|-------------|--------------|
| `workflowId` | Workflow instance ID | `"8fa4b8d0-0c57-79d8-a7d2-6462819c23a7"` |
| `workflowName` | Workflow definition name | `"orderProcessing"` |
| `workflowVersion` | Workflow version | `"1.0"` |
| `nodePosition` | Current position in workflow | `"/do/0/callHTTP"` |
| `correlationId` | Request correlation ID | `"req-123-abc"` |
| `requestId` | Unique request ID | `"req-123-abc"` |

---

## Configuration

### Basic Configuration (application.properties)

```properties
# Root logger level
quarkus.log.level=INFO

# Category-specific levels
quarkus.log.category."com.lemline".level=INFO
quarkus.log.category."com.lemline.core".level=DEBUG
quarkus.log.category."com.lemline.runner.messaging".level=DEBUG

# Console logging
quarkus.log.console.enable=true
quarkus.log.console.format=%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%c{2.}] (%t) %s%e%n
```

### JSON Logging for Production

```properties
# Enable JSON logging
quarkus.log.console.json=true

# Configure JSON fields
quarkus.log.console.json.pretty-print=false
quarkus.log.console.json.date-format=yyyy-MM-dd HH:mm:ss.SSS
quarkus.log.console.json.record-delimiter=\n
quarkus.log.console.json.exception-output-type=formatted
quarkus.log.console.json.additional-field.app-name.value=lemline
```

### Environment-Specific Configuration

```properties
# Development profile
%dev.quarkus.log.console.json=false
%dev.quarkus.log.console.format=%d{HH:mm:ss.SSS} %-5p [%c{2.}] (%t) %s%e%n
%dev.quarkus.log.category."com.lemline".level=DEBUG

# Production profile
%prod.quarkus.log.console.json=true
%prod.quarkus.log.level=INFO
%prod.quarkus.log.category."com.lemline".level=INFO
```

---

## Best Practices

### Do's

1. **Use lambda expressions** for log messages:
   ```kotlin
   // Good - only evaluated if INFO level is enabled
   logger.info { "Processing item ${item.id}" }

   // Bad - string concatenation happens regardless of log level
   logger.info("Processing item " + item.id)
   ```

2. **Include contextual information** using MDC:
   ```kotlin
   MDC.put("userId", user.id)
   logger.info { "User logged in" }
   ```

3. **Clean up MDC** after use:
   ```kotlin
   try {
       MDC.put("key", "value")
       // Operations
   } finally {
       MDC.remove("key")
   }
   ```

4. **Use appropriate log levels** based on the information's importance

### Don'ts

1. **Don't log sensitive information** like passwords, tokens, or PII:
   ```kotlin
   // Bad
   logger.info { "User ${user.email} logged in with password $password" }

   // Good
   logger.info { "User ${user.id} logged in" }
   ```

2. **Don't use string concatenation** in log messages:
   ```kotlin
   // Bad
   logger.debug("Value: " + expensiveOperation())

   // Good
   logger.debug { "Value: ${expensiveOperation()}" }
   ```

3. **Don't overuse INFO level** for debugging:
   ```kotlin
   // Bad - too verbose for info level
   logger.info { "Entering method processItem with item $item" }

   // Good
   logger.debug { "Entering method processItem with item $item" }
   ```

4. **Don't log exceptions without context**:
   ```kotlin
   // Bad
   logger.error(e)

   // Good
   logger.error(e) { "Failed to process order #$orderId" }
   ```

---

## Workflow-Specific Logging

```kotlin
class WorkflowLogger(private val logger: KLogger) {
    fun logStart(workflowId: String, name: String, version: String) {
        MDC.put("workflowId", workflowId)
        MDC.put("workflowName", name)
        MDC.put("workflowVersion", version)
        logger.info { "Workflow started" }
    }

    fun logCompletion(workflowId: String) {
        logger.info { "Workflow completed" }
        MDC.clear()
    }

    fun logError(workflowId: String, error: Throwable) {
        logger.error(error) { "Workflow failed" }
        MDC.clear()
    }
}
```

---

## Troubleshooting

### Common Issues

- **Missing context information**: Ensure MDC values are set and cleared appropriately
- **Performance issues**: Check for excessive logging at INFO level
- **Log message formatting issues**: Verify log format configuration
- **Missing log entries**: Check log level configuration for the specific category

### Viewing Logs

```bash
# View application logs
java -jar lemline-runner.jar

# Enable debug logging
java -jar lemline-runner.jar -Dquarkus.log.level=DEBUG

# Enable debug logging for specific categories
java -jar lemline-runner.jar -Dquarkus.log.category."com.lemline.core".level=DEBUG
```

### Log Analysis

For log analysis in production:

1. Enable JSON logging
2. Use log aggregation tools (ELK Stack, Graylog, etc.)
3. Create dashboards for common workflow events
4. Set up alerts for ERROR level logs
