# Error Handling

## Overview

Lemline implements a comprehensive error handling strategy to manage failures during workflow execution, task processing, and system operations. The approach is designed to:

- Provide clear, typed exceptions for different error categories
- Ensure proper error propagation and isolation
- Support retry mechanisms for transient failures
- Prevent cascading failures with circuit breakers
- Enable appropriate compensation actions
- Facilitate debugging with contextual logging

## Exception Hierarchy

```
Exception
├── WorkflowException
│   ├── ConfigurationException
│   ├── ValidationException
│   ├── ExpressionException
│   └── RuntimeException
└── SystemException
    ├── DatabaseException
    ├── MessagingException
    └── InfrastructureException
```

### WorkflowException

`WorkflowException` is the base class for workflow-related errors:

```kotlin
class WorkflowException(
    val error: WorkflowError,
    val position: NodePosition? = null,
    cause: Throwable? = null
) : Exception(error.title, cause) {
    constructor(message: String, cause: Throwable? = null) : this(
        WorkflowError(ErrorType.RUNTIME, message),
        null,
        cause
    )
}
```

The `WorkflowError` class contains structured error information:

```kotlin
data class WorkflowError(
    val type: ErrorType,
    val title: String,
    val details: String? = null,
    val status: Int? = null,
    val source: NodePosition? = null
)

enum class ErrorType {
    CONFIGURATION,  // Error in workflow definition or configuration
    VALIDATION,     // Data validation failure
    EXPRESSION,     // Expression evaluation error
    RUNTIME         // Error during workflow execution
}
```

### SystemException

`SystemException` is the base class for system-level errors:

```kotlin
class SystemException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
```

## Error Handling Mechanisms

### 1. Try-Catch Mechanism

Workflow definitions can include try-catch blocks for error handling:

```yaml
try:
  do:
    - callHTTP:
        url: "http://example.com/api"
  catch:
    - error: NotFound     # Match by error name
      status: 404         # Match by status code
      when: "${ .payload.retry == false }"  # Conditional handling
      as: "notFoundError" # Store error in variable
      do:
        - log:
            message: "Resource not found: ${ .notFoundError.details }"
```

Implemented in `TryInstance`:

```kotlin
class TryInstance(
    position: NodePosition,
    task: TryTask,
    parent: NodeInstance<*>?
) : NodeInstance<TryTask>(position, task, parent) {
    
    override fun execute(): NodeState {
        try {
            // Execute the main do block
            val result = doInstance.execute()
            return result
        } catch (e: WorkflowException) {
            // Find a matching catch block
            val catcher = catches.firstOrNull { it.isCatching(e.error) }
            
            if (catcher != null) {
                // Handle the error in the catch block
                val catchError = e.error.copy(source = e.position)
                catcher.setError(catchError)
                return catcher.execute()
            } else {
                // No matching catch, rethrow the error
                throw e
            }
        }
    }
}
```

### 2. Retry Mechanism

Workflow definitions can include retry policies for transient errors:

```yaml
try:
  retry:
    policy:
      strategy: backoff
      backoff:
        delay: PT1S
        multiplier: 2
        jitter: 0.1
      limit:
        attempt:
          count: 5
        duration: PT5M
  do:
    - callHTTP:
        url: "http://example.com/api"
```

Implemented in `RetryPolicy`:

```kotlin
class RetryPolicy(
    val maxAttempts: Int = 3,
    val delay: Duration = Duration.ofSeconds(1),
    val multiplier: Double = 1.0,
    val jitter: Double = 0.0,
    val maxDuration: Duration? = null,
    val whenCondition: String? = null,
    val exceptWhenCondition: String? = null
) {
    fun shouldRetry(attempt: Int, error: WorkflowError, context: JsonElement): Boolean {
        // Check if we've exceeded the maximum attempts
        if (attempt >= maxAttempts) return false
        
        // Evaluate conditions if specified
        if (whenCondition != null) {
            val result = expressionEngine.evalBoolean(whenCondition, context)
            if (!result) return false
        }
        
        if (exceptWhenCondition != null) {
            val result = expressionEngine.evalBoolean(exceptWhenCondition, context)
            if (result) return false
        }
        
        return true
    }
    
    fun calculateDelay(attempt: Int): Duration {
        var baseDelay = delay.toMillis()
        
        // Apply multiplier for backoff
        if (multiplier > 1.0) {
            baseDelay = (baseDelay * Math.pow(multiplier, attempt.toDouble())).toLong()
        }
        
        // Apply jitter if specified
        if (jitter > 0) {
            val jitterAmount = (baseDelay * jitter * (Math.random() * 2 - 1)).toLong()
            baseDelay += jitterAmount
        }
        
        return Duration.ofMillis(baseDelay)
    }
}
```

### 3. Circuit Breaker

For external service calls, Lemline integrates circuit breakers to prevent cascading failures:

```kotlin
@ApplicationScoped
class HttpClient {
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5,
        delay = 5000,
        successThreshold = 2
    )
    fun executeHttpRequest(request: HttpRequest): Response {
        // Make HTTP request
    }
}
```

### 4. Compensation Logic

For operations that need to be rolled back, Lemline supports compensation:

```yaml
compensate:
  do:
    - name: "createOrder"
      callHTTP:
          url: "http://example.com/orders"
          method: "POST"
  compensationDo:
    - callHTTP:
        url: "http://example.com/orders/${ .orderId }"
        method: "DELETE"
```

Implemented in `CompensateInstance`:

```kotlin
class CompensateInstance(
    position: NodePosition,
    task: CompensateTask,
    parent: NodeInstance<*>?
) : NodeInstance<CompensateTask>(position, task, parent) {
    
    private val compensations = mutableListOf<NodeInstance<*>>()
    
    override fun execute(): NodeState {
        try {
            // Execute the main do block
            val result = doInstance.execute()
            return result
        } catch (e: WorkflowException) {
            // Execute compensations in reverse order
            for (compensation in compensations.reversed()) {
                try {
                    compensation.execute()
                } catch (ce: Exception) {
                    logger.error("Compensation failed", ce)
                }
            }
            throw e
        }
    }
}
```

## Error Logging

Lemline implements contextual error logging to facilitate debugging:

```kotlin
try {
    // Operation that may fail
} catch (e: WorkflowException) {
    logger.error(
        "Workflow execution failed",
        kv("workflowId", workflowId),
        kv("nodePosition", e.position),
        kv("errorType", e.error.type),
        kv("errorTitle", e.error.title),
        kv("errorDetails", e.error.details),
        e
    )
}
```

The log includes:
- Workflow identifier
- Node position where the error occurred
- Error type and description
- Contextual information
- Stack trace

## Custom Error Types

You can define custom error types for your specific workflow needs:

```kotlin
// Define a custom error type
object ErrorCatalog {
    val PAYMENT_DECLINED = WorkflowError(
        type = ErrorType.RUNTIME,
        title = "Payment Declined",
        status = 400
    )
    
    val SERVICE_UNAVAILABLE = WorkflowError(
        type = ErrorType.RUNTIME,
        title = "Service Temporarily Unavailable",
        status = 503
    )
}

// Use the custom error type
throw WorkflowException(
    error = ErrorCatalog.PAYMENT_DECLINED.copy(
        details = "Insufficient funds"
    ),
    position = nodePosition
)
```

## Best Practices

### Error Handling Guidelines

1. **Be specific with error types**: Use the most specific exception type for the error scenario
2. **Include contextual information**: Always include relevant context in error messages
3. **Handle recoverable errors**: Use retry mechanisms for transient failures
4. **Fail fast for non-recoverable errors**: Don't retry when recovery is not possible
5. **Log at appropriate levels**:
   - ERROR: For actual error conditions
   - WARN: For potential issues that don't prevent operation
   - INFO: For normal system events
6. **Use structured error data**: Prefer structured error objects over plain strings

### When to Use Different Error Handling Mechanisms

| Mechanism | When to Use |
|-----------|-------------|
| Try-Catch | For anticipated errors that can be handled within the workflow |
| Retry | For transient failures (network issues, timeouts) |
| Circuit Breaker | For protecting against failing external dependencies |
| Compensation | For rolling back previous steps after a failure |
| Global Error Handler | For last-resort error handling and logging |

## Implementing Custom Error Handling

### Custom Exception Types

```kotlin
class CustomWorkflowException(
    error: WorkflowError,
    position: NodePosition? = null,
    cause: Throwable? = null
) : WorkflowException(error, position, cause) {
    // Custom properties and methods
}
```

### Custom Error Handler

```kotlin
@ApplicationScoped
class CustomErrorHandler {
    @Inject
    lateinit var logger: Logger
    
    fun handleError(error: WorkflowError, context: Map<String, Any>): ErrorResolution {
        // Custom error handling logic
        
        // Decide on resolution strategy
        return when (error.type) {
            ErrorType.CONFIGURATION -> ErrorResolution.FAIL_FAST
            ErrorType.VALIDATION -> ErrorResolution.COMPENSATE
            ErrorType.EXPRESSION -> ErrorResolution.RETRY
            ErrorType.RUNTIME -> {
                if (error.status in 500..599) ErrorResolution.RETRY
                else ErrorResolution.FAIL_FAST
            }
        }
    }
}

enum class ErrorResolution {
    RETRY,
    COMPENSATE,
    FAIL_FAST
}
```

## Troubleshooting

### Common Error Patterns

| Error Pattern | Likely Cause | Solution |
|---------------|--------------|----------|
| Repeated retries of the same operation | External service is down | Implement circuit breaker |
| Cascading failures | Dependency failures without isolation | Implement bulkheads and timeouts |
| Excessive compensations | Workflow design with many failure points | Redesign workflow for better isolation |
| Unhandled exceptions | Missing error handling for specific cases | Add catch blocks for all error types |

### Debugging Error Handling

To debug error handling:

1. Enable detailed logging:
   ```properties
   quarkus.log.category."com.lemline.core.execution".level=DEBUG
   quarkus.log.category."com.lemline.core.error".level=TRACE
   ```

2. Use error tracing:
   ```kotlin
   val trace = workflowService.getErrorTrace(workflowId)
   ```

3. Inspect retry statistics:
   ```kotlin
   val retryStats = retryService.getRetryStatistics()
   ```

### Adding Breakpoints

When debugging in your IDE, add breakpoints in:

1. `NodeInstance.raise()` - Where errors are initially raised
2. `TryInstance.execute()` - Where errors are caught and handled
3. `RetryPolicy.shouldRetry()` - Where retry decisions are made
4. `WorkflowInstance.handleError()` - Where unhandled errors are processed 