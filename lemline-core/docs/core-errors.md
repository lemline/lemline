# Error Handling

## Overview

Two exception types: `AsyncTaskException` for orchestration signals (wait, fork, child workflow) and `InternalException` for domain errors. TryTask provides retry and catch blocks.

## Key Files

| File | Purpose |
|------|---------|
| `errors/AsyncTaskException.kt` | Orchestration signals |
| `errors/WorkflowException.kt` | Domain errors |
| `processors/TryProcessor.kt` | Retry/catch logic |
| `utils/Retry.kt` | Backoff calculation |

---

## Exception Hierarchy

```
AsyncTaskException (orchestration signals - not errors)
├── WaitStartedException(state, config.waitUntil)
├── RunWorkflowStartedException(state, config: namespace/name/version/input/sync)
└── ForkStartedException(state, transformedInput)

InternalException (domain errors)
└── Error(errorType, title, details?, status, position)
```

---

## WorkflowErrorType

| Type | Description |
|------|-------------|
| `EXPRESSION` | JQ evaluation failure |
| `VALIDATION` | Schema validation error |
| `CONFIGURATION` | Workflow definition issue |
| `RUNTIME` | General execution error |
| `AUTHENTICATION` | Auth failure |
| `TIMEOUT` | Operation timeout |
| `COMMUNICATION` | Network/service error |

---

## Creating Errors

```kotlin
throw InternalException(InternalException.Error(
    errorType = WorkflowErrorType.COMMUNICATION,
    title = "HTTP request failed",
    details = responseBody,
    status = 503,
    position = node.position
))
```

---

## TryTask DSL

```yaml
try:
  do:
    - riskyOperation:
        call: http
        with:
          endpoint: https://api.example.com
  catch:
    errors:
      with:
        type: communication   # Filter by type
        status: 503           # Filter by status
      when: ".error.status >= 500"      # JQ condition
      exceptWhen: ".error.status == 501"
    retry:
      limit:
        attempt:
          count: 3            # Max attempts
          duration: PT5M      # Max per attempt
        duration: PT1H        # Total max time
      delay: PT1S             # Base delay
      backoff:
        exponential:
          multiplier: 2
      jitter: PT100ms
    do:
      - handleError:          # Catch block
          set:
            error: "$error"
```

---

## Error Handling Flow

```
Task throws InternalException
    │
    ▼
tryCatch() walks parent chain
    │
    ├── ForkTask found → BranchFailed (error boundary)
    │
    ├── TryTask found → isCatching()?
    │       │
    │       ├── No → continue search
    │       │
    │       └── Yes → shouldRetry()?
    │               │
    │               ├── Yes → RetryScheduled
    │               │
    │               └── No → execute catch block
    │
    └── No handler → WorkflowFailed
```

---

## TryProcessor Methods

### isCatching

```kotlin
fun isCatching(error: Error, scope: Scope): Boolean {
    // Match by type: errors.with.type
    // Match by status: errors.with.status
    // Match by when: JQ expression
    // Exclude by exceptWhen: JQ expression
}
```

### shouldRetry

```kotlin
fun shouldRetry(state: TryState, error: Error): RetryDecision {
    // Check attempt count limit
    // Check total duration limit
    // Calculate delay with backoff + jitter
    // Return Retry(delay, newState) or NoRetry
}
```

---

## Backoff Calculation

```kotlin
fun calculateRetryDelay(attemptIndex: Int, baseDelay: Duration,
                        backoff: Backoff?, jitter: Duration?): Duration
```

| Strategy | Formula | Example (1s base) |
|----------|---------|-------------------|
| Constant | `delay` | 1s, 1s, 1s |
| Linear | `delay + (increment × attempt)` | 1s, 1.5s, 2s |
| Exponential | `delay × multiplier^attempt` | 1s, 2s, 4s |

---

## tryCatch Implementation

```kotlin
fun tryCatch(workflow: Workflow, error: Error, taskStates: TaskStates): WorkflowEvent {
    var current = error.position
    while (current != NodePosition.root) {
        val node = workflow.getNode(current)

        // Fork is error boundary
        if (node.task is ForkTask) {
            return forkBranchFailed(...) ?: WorkflowFailed(error)
        }

        // Check TryTask
        if (node.task is TryTask) {
            val processor = TryProcessor(node)
            if (processor.isCatching(error, scope)) {
                return when (processor.shouldRetry(state, error)) {
                    is Retry -> RetryScheduled(...)
                    is NoRetry -> TaskScheduled(catch position)
                }
            }
        }
        current = current.parent ?: break
    }
    return WorkflowFailed(error)
}
```

---

## Testing

```kotlin
@Test
fun `should retry then catch`() = runTest {
    val yaml = """
        try:
          do:
            - alwaysFails:
                raise:
                  error: { type: runtime, status: 500, title: "Fail" }
          catch:
            retry:
              limit:
                attempt:
                  count: 2
            do:
              - handleError:
                  set: { handled: true }
    """.trimIndent()

    val result = executeWorkflow(yaml)
    assertEquals(true, result["handled"]?.jsonPrimitive?.boolean)
}
```

---

## Common Issues

| Issue | Check |
|-------|-------|
| Retry not triggered | `errors.with.type` matches error |
| Infinite retry | `limit.attempt.count` is set |
| Catch not executing | `isCatching()` returns true |
| Wrong error variable | Default is `$error`, check `as` |
