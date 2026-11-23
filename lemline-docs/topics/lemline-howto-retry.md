# Implementing Retry Mechanisms

This guide explains how to configure retry mechanisms in Lemline to handle transient failures automatically.

## Understanding Retry Policies

Retry policies allow workflows to automatically attempt operations again after failures, making your workflows more resilient to temporary issues like network problems, service unavailability, or rate limiting.

Lemline provides powerful, configurable retry capabilities that can be tailored to specific error conditions and scenarios.

## Basic Retry Configuration

A basic retry configuration can be added to a `try` block:

```yaml
- fetchDataTask:
    try:
      retry:
        policy:
          strategy: backoff
          backoff:
            delay: PT1S
            multiplier: 2
          limit:
            attempt:
              count: 3
      do:
        - getData:
            callHTTP:
              url: "http://api.example.com/data"
              method: "GET"
```

In this example:
- The `getData` task will be retried up to 3 times if it fails
- The first retry will happen after 1 second (`delay: PT1S`)
- Each subsequent retry will wait twice as long as the previous delay (`multiplier: 2`)

## Retry Strategies

Lemline supports different retry strategies:

### Constant Delay

```yaml
retry:
  policy:
    strategy: constant
    constant:
      delay: PT3S
    limit:
      attempt:
        count: 5
```

This configuration uses a constant 3-second delay between retry attempts.

### Exponential Backoff

```yaml
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
```

This configuration:
- Starts with a 1-second delay
- Doubles the delay with each attempt (multiplier: 2)
- Adds random jitter (±10%) to prevent synchronized retries

### Linear Backoff

```yaml
retry:
  policy:
    strategy: linear
    linear:
      delay: PT1S
      increment: PT2S
    limit:
      attempt:
        count: 4
```

This configuration:
- Starts with a 1-second delay
- Adds 2 seconds to each subsequent delay (1s, 3s, 5s, 7s)

## Setting Retry Limits

You can limit retries by:

### Maximum Attempts

```yaml
limit:
  attempt:
    count: 5
```

Specifies a maximum of 5 retry attempts.

### Maximum Duration

```yaml
limit:
  duration: PT1M
```

Limits the total retry period to 1 minute.

### Combining Limits

```yaml
limit:
  attempt:
    count: 5
  duration: PT1M
```

Retries will stop when either the attempt count or the duration limit is reached.

## Conditional Retries

Control when retries occur using conditions:

```yaml
retry:
  policy:
    strategy: backoff
    backoff:
      delay: PT1S
      multiplier: 2
    limit:
      attempt:
        count: 3
    when: "${ .error.status >= 500 && .error.status < 600 }"
```

This configuration only triggers retries for server errors (5xx status codes).

## Preventing Retries

Explicitly prevent retries for certain conditions:

```yaml
retry:
  policy:
    strategy: backoff
    backoff:
      delay: PT1S
    limit:
      attempt:
        count: 3
    exceptWhen: "${ .error.status == 404 || .error.status == 400 }"
```

This prevents retries for 404 (Not Found) and 400 (Bad Request) errors, which are unlikely to be resolved by retrying.

## Reusing Retry Policies

Define reusable retry policies:

```yaml
use:
  retries:
    - name: "standardRetry"
      policy:
        strategy: backoff
        backoff:
          delay: PT1S
          multiplier: 2
          jitter: 0.1
        limit:
          attempt:
            count: 3
    - name: "aggressiveRetry"
      policy:
        strategy: backoff
        backoff:
          delay: PT0.1S
          multiplier: 1.5
        limit:
          attempt:
            count: 10

# Using the reusable policy
- fetchDataTask:
    try:
      retry: "standardRetry"
      do:
        # ...
```

This approach promotes consistent retry behavior across your workflow and simplifies maintenance.

## Best Practices

1. **Choose appropriate delays**: Short delays for quick-to-recover issues, longer delays for system overload.
2. **Use exponential backoff with jitter**: Prevents retry storms and gives external systems time to recover.
3. **Set reasonable limits**: Avoid infinite retry loops with appropriate count or duration limits.
4. **Be selective**: Only retry operations that have a chance of succeeding on retry.
5. **Consider error types**: Different errors may require different retry strategies.
6. **Monitor retry patterns**: High retry rates may indicate underlying issues that need to be addressed.

## Related Resources

- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [Resilience Patterns](dsl-resilience-patterns.md)
- [Custom Error Types](lemline-howto-custom-errors.md)
- [Debugging Workflows](lemline-howto-debug.md)