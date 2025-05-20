# Resilience Patterns

## Purpose

In distributed systems, failures are inevitable. Lemline implements several resilience patterns to ensure that workflows can gracefully handle these failures and maintain system stability. These patterns help in creating robust, fault-tolerant workflows that can recover from transient failures and provide consistent behavior even when external dependencies fail.

Resilience patterns in Lemline enable:

* Graceful handling of transient failures
* Isolation of failing components
* Consistent behavior during outages
* Progressive recovery from failures
* Improved overall system reliability

## Retry Pattern

### Purpose

The retry pattern allows workflows to automatically attempt an operation multiple times when a transient failure occurs, improving the chances of eventual success without manual intervention.

### Implementation

In Lemline, retries are configured in the `try` block with a detailed policy:

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

### Key Components

* **Initial delay**: The time to wait before the first retry (`delay`)
* **Backoff strategy**: How subsequent delays increase (`strategy` and `multiplier`)
* **Jitter**: Random variation to prevent synchronized retries (`jitter`)
* **Limits**: Maximum attempts (`count`) or total duration (`duration`)
* **Conditional retries**: Only retry for specific error conditions (`when`, `exceptWhen`)

### Best Practices

1. **Use exponential backoff**: Start with short delays and increase exponentially
2. **Add jitter**: Prevent synchronized retry storms with randomized delays
3. **Set appropriate limits**: Don't retry indefinitely; set reasonable attempt counts
4. **Be selective**: Only retry for errors that are likely to be transient
5. **Monitor retry rates**: High retry rates may indicate underlying issues

## Circuit Breaker Pattern

### Purpose

The circuit breaker pattern prevents a failing service from being repeatedly called, which can cascade failures throughout the system. It "trips" after a threshold of failures, temporarily blocking further calls to allow the service to recover.

### Implementation

Lemline integrates circuit breakers for tasks that communicate with external services:

1. **Closed state** (normal operation): Calls pass through normally, but failures are counted
2. **Open state** (tripped): Calls immediately fail without attempting the operation
3. **Half-open state** (recovery): After a timeout, limited calls are allowed to test recovery

### Configuration

Circuit breakers can be configured through the Quarkus fault tolerance API:

```yaml
# Configuration in application.properties
lemline.circuit-breaker.http-client.failure-threshold=50
lemline.circuit-breaker.http-client.success-threshold=3
lemline.circuit-breaker.http-client.delay=PT5S
lemline.circuit-breaker.http-client.timeout=PT30S
```

### Using With HTTP Calls

Circuit breakers automatically protect `callHTTP`, `callGRPC`, and other external service calls:

```yaml
- checkInventory:
    callHTTP:
      url: "http://inventory-service/api/check"
      method: "GET"
      # This call is protected by circuit breaker
```

If the circuit is open due to previous failures, the call will fail immediately with a `CircuitBreakerOpenException` that can be caught and handled.

## Timeout Pattern

### Purpose

The timeout pattern prevents operations from blocking indefinitely by setting maximum allowed durations for tasks and workflows, ensuring resource release and preventing deadlocks.

### Implementation

Timeouts can be configured at multiple levels:

1. **Task-level timeouts**: For individual tasks
2. **Workflow-level timeouts**: For the entire workflow execution
3. **Operation-specific timeouts**: For specific operations like HTTP calls

### Configuration

```yaml
# Task-level timeout
- fetchData:
    callHTTP:
      url: "http://data-service/api/data"
      timeout: PT10S  # 10-second timeout

# Workflow-level timeout
workflow:
  timeout: PT5M  # 5-minute timeout for entire workflow
```

### Error Handling

When timeouts occur, they raise a standard `timeout` error:

```yaml
try:
  do:
    - longRunningTask:
        callHTTP:
          url: "http://slow-service/api"
          timeout: PT5S
  catch:
    - error:
        with:
          type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
      do:
        - fallback:
            set:
              result: "default-value"
```

## Bulkhead Pattern

### Purpose

The bulkhead pattern isolates elements of an application into pools so that if one fails, the others continue to function. It's named after the compartments in a ship that prevent the entire vessel from flooding when one section is damaged.

### Implementation

Lemline implements bulkheads through concurrent execution limits:

1. **Thread pools**: Separate thread pools for different types of operations
2. **Semaphores**: Limiting concurrent execution of specific operations
3. **Resource isolation**: Preventing resource exhaustion from cascading

### Configuration

```yaml
# Configuration in application.properties
lemline.bulkhead.http-client.max-concurrent-calls=20
lemline.bulkhead.database.max-concurrent-operations=10
```

### Usage with Fork Tasks

The `fork` task automatically implements a form of bulkhead pattern by isolating parallel execution paths:

```yaml
- processBatch:
    fork:
      branches:
        - processItems:
            # ... operations ...
        - updateInventory:
            # ... operations ...
      # If one branch fails, it doesn't affect others
```

## Health Checks

### Purpose

Health checks provide continuous monitoring of system components and services to detect failures early and trigger appropriate resilience mechanisms.

### Implementation

Lemline integrates with Quarkus health checks:

1. **Liveness checks**: Verify if the application is running
2. **Readiness checks**: Verify if the application is ready to handle requests
3. **External service health**: Monitor the health of external dependencies

### Configuration

Health checks are automatically registered for internal and configured external services.

## Progressive Recovery

### Purpose

Progressive recovery enables systems to heal gradually after failures, preventing overload during recovery periods.

### Implementation

Lemline implements progressive recovery through:

1. **Rate limiting**: Gradually increasing workload after recovery
2. **Prioritization**: Processing critical work first during recovery
3. **Backpressure**: Controlling ingestion rates to prevent overwhelming the system

### Rate Limiting Configuration

```yaml
# Configuration in application.properties
lemline.rate-limit.recovery.initial-rate=10
lemline.rate-limit.recovery.max-rate=100
lemline.rate-limit.recovery.increase-factor=1.5
```

## Best Practices

### When to Use Each Pattern

| Pattern | When to Use |
|---------|-------------|
| Retry | For transient failures in external services |
| Circuit Breaker | For protecting against persistent external service failures |
| Timeout | For operations that might block indefinitely |
| Bulkhead | For isolating critical paths from failures in non-critical ones |
| Health Checks | For continuous monitoring and early failure detection |
| Progressive Recovery | For gradually recovering from system-wide failures |

### Combined Patterns

These patterns are most effective when combined. Common combinations include:

1. **Retry + Circuit Breaker**: Retry transient failures but trip circuit breaker for persistent ones
2. **Timeout + Retry**: Set timeouts to prevent indefinite blocking, then retry
3. **Bulkhead + Circuit Breaker**: Isolate components and prevent cascading failures
4. **Health Checks + Progressive Recovery**: Use health monitoring to guide recovery process

### Implementation Considerations

1. **Failure detection**: Define clear criteria for what constitutes a failure
2. **Fallback strategies**: Always provide graceful degradation paths
3. **Recovery thresholds**: Set appropriate thresholds for recovery
4. **Monitoring**: Implement comprehensive monitoring to track resilience pattern behavior
5. **Testing**: Regularly test resilience patterns with chaos engineering techniques

## Example: Complete Resilience Implementation

```yaml
- processOrder:
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
              count: 3
      do:
        - submitOrder:
            callHTTP:
              url: "http://order-service/api/orders"
              method: "POST"
              timeout: PT5S  # Timeout pattern
    catch:
      # Fallback strategy
      - error:
          with:
            type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
        do:
          - queueForLater:
              emit:
                event: "OrderPending"
                data: "${ .order }"
```

This example combines multiple resilience patterns:
- **Retry pattern** with exponential backoff for transient failures
- **Timeout pattern** to prevent indefinite blocking
- **Circuit breaker** (implicitly applied to HTTP calls)
- **Fallback strategy** through the catch block