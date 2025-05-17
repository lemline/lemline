# Logging Strategy

## Overview

Lemline implements a comprehensive logging strategy based on SLF4J with Quarkus configuration. The logging system is designed to:

- Provide consistent log formats across all modules
- Include contextual information through MDC (Mapped Diagnostic Context)
- Use appropriate log levels for different types of events
- Support structured logging in JSON format
- Use efficient lambda-based log messages
- Configure logging differently for development and production

## Using Logging in Lemline

### Basic Logging

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

### Contextual Logging

Lemline uses MDC (Mapped Diagnostic Context) to include context information in logs:

```kotlin
// Setting context
MDC.put("workflowId", workflowId)
MDC.put("nodePosition", nodePosition.toString())

try {
    // Operations with the context set
    logger.info { "Executing workflow node" }
} finally {
    // Clear context
    MDC.remove("workflowId")
    MDC.remove("nodePosition")
}
```

Or using a scope function for automatic cleanup:

```kotlin
withLoggingContext(
    "workflowId" to workflowId,
    "nodePosition" to nodePosition.toString()
) {
    logger.info { "Executing workflow node" }
}
```

### Structured Event Logging

For complex event logging with multiple fields:

```kotlin
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KLogging.Companion.kv

logger.info(
    message = "Workflow instance started",
    kv("workflowId", instance.id),
    kv("workflowName", instance.name),
    kv("workflowVersion", instance.version),
    kv("correlationId", instance.correlationId),
    kv("input", instance.input.toJson())
)
```

## Standard Context Keys

Lemline defines standard context keys to use across the codebase:

| Context Key | Description | Example Value |
|-------------|-------------|--------------|
| `workflowId` | ID of the workflow instance | `"8fa4b8d0-0c57-79d8-a7d2-6462819c23a7"` |
| `workflowName` | Name of the workflow | `"orderProcessing"` |
| `workflowVersion` | Version of the workflow | `"1.0"` |
| `nodePosition` | Position in the workflow | `"/do/0/callHTTP"` |
| `correlationId` | Correlation ID for requests | `"req-123-abc"` |
| `requestId` | Unique ID for each request | `"req-123-abc"` |
| `userId` | ID of the user making the request | `"user-123"` |

## Log Levels

Use the appropriate log level:

- **TRACE**: Very detailed information, used only for tracing code execution
- **DEBUG**: Detailed information useful for debugging
- **INFO**: General information about system operation
- **WARN**: Warnings about potential issues
- **ERROR**: Errors that don't prevent the system from running
- **FATAL**: Critical errors that prevent the system from running (rarely used)

### When to Use Each Level

| Level | Use Case | Example |
|-------|----------|---------|
| TRACE | Detailed flow of execution | "Entering method x with parameters a=1, b=2" |
| DEBUG | Information useful for debugging | "HTTP request sent to endpoint x" |
| INFO | Normal events | "Workflow 'order-processing' started" |
| WARN | Potential issues | "Database connection pool approaching limit" |
| ERROR | Runtime errors | "Failed to process HTTP request" |

## Configuration

### Basic Configuration

Configure logging in `application.properties`:

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

For production, use JSON format to facilitate log aggregation:

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

Use Quarkus profiles to configure different logging for different environments:

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

## MDC Integration

Lemline integrates MDC with various system components:

### HTTP Request Processing

```kotlin
@ServerRequestFilter
class LoggingFilter {
    @Inject
    lateinit var logger: Logger
    
    fun filter(request: ServerRequest) {
        val requestId = UUID.randomUUID().toString()
        
        MDC.put("requestId", requestId)
        MDC.put("method", request.method)
        MDC.put("path", request.path)
        MDC.put("userAgent", request.headers.getFirst("User-Agent").orElse("unknown"))
        
        // Log request
        logger.info("Request received")
        
        try {
            // Process request
            return chain.next(request)
        } finally {
            // Log response
            logger.info("Response sent")
            
            // Clear MDC
            MDC.clear()
        }
    }
}
```

### Workflow Execution

```kotlin
class WorkflowExecutor {
    fun execute(workflowId: String, input: JsonObject) {
        MDC.put("workflowId", workflowId)
        
        try {
            // Execute workflow
            logger.info("Workflow execution started")
            
            // ... workflow execution ...
            
            logger.info("Workflow execution completed")
        } catch (e: Exception) {
            logger.error(e) { "Workflow execution failed" }
            throw e
        } finally {
            MDC.remove("workflowId")
        }
    }
}
```

## Best Practices

### Do's

1. **Use lambda expressions** for log messages to avoid string concatenation when logs are disabled:
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

3. **Use structured logging** for complex events:
   ```kotlin
   logger.info(
       message = "Order created",
       kv("orderId", order.id),
       kv("amount", order.amount),
       kv("customer", order.customerId)
   )
   ```

4. **Clean up MDC** after use:
   ```kotlin
   try {
       MDC.put("key", "value")
       // Operations
   } finally {
       MDC.remove("key")
   }
   ```

5. **Use appropriate log levels** based on the information's importance

### Don'ts

1. **Don't log sensitive information** like passwords, tokens, or personally identifiable information (PII):
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

3. **Don't overuse high-level logs** like INFO for debugging:
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

## Extending the Logging System

### Custom MDC Values

To add custom MDC values, update the `LoggingFilter` class:

```kotlin
class LoggingFilter {
    fun filter(request: ServerRequest) {
        // Existing code...
        
        // Add custom MDC value
        request.headers.getFirst("X-Tenant-ID").ifPresent { 
            MDC.put("tenantId", it)
        }
        
        // Continue processing...
    }
}
```

### Custom Log Format

To customize the log format, update `application.properties`:

```properties
# Custom log format
quarkus.log.console.format=%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%X{requestId}] [%c{2.}] %s%e%n
```

### Custom Logger Implementation

For specialized logging needs, create a custom logger wrapper:

```kotlin
class WorkflowLogger(private val logger: Logger) {
    fun logStart(workflowId: String, name: String, version: String, input: JsonElement) {
        logger.info(
            message = "Workflow started",
            kv("workflowId", workflowId),
            kv("name", name),
            kv("version", version),
            kv("input", input.toString())
        )
    }
    
    fun logCompletion(workflowId: String, output: JsonElement) {
        logger.info(
            message = "Workflow completed",
            kv("workflowId", workflowId),
            kv("output", output.toString())
        )
    }
    
    fun logError(workflowId: String, error: WorkflowError) {
        logger.error(
            message = "Workflow failed",
            kv("workflowId", workflowId),
            kv("errorType", error.type),
            kv("errorTitle", error.title),
            kv("errorDetails", error.details)
        )
    }
}
```

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

For log analysis:

1. Enable JSON logging in production
2. Use log aggregation tools (ELK Stack, Graylog, etc.)
3. Create dashboards for common workflow events
4. Set up alerts for ERROR level logs 