# Error Handling with Try-Catch

This guide explains how to implement error handling in your workflows using the Try-Catch mechanism provided by Lemline.

## Using Try-Catch

The Try-Catch mechanism allows you to handle errors that occur during workflow execution in a structured way. This approach prevents workflow failures by catching exceptions and providing fallback behavior.

### Basic Try-Catch Structure

```yaml
- errorHandlingTask:
    try:
      do:
        - riskyOperation:
            callHTTP:
              url: "http://example.com/api"
              method: "GET"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
          do:
            - handleError:
                set:
                  status: "error"
                  message: "API call failed, using default values"
```

The above example:
1. Attempts to call an HTTP endpoint
2. Catches any communication errors that might occur
3. Sets default values if the API call fails

### Catching Specific Errors

You can catch specific error types using the `with` property:

```yaml
catch:
  - error:
      with:
        type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
    do:
      # Handle timeout errors
  - error:
      with:
        type: "https://serverlessworkflow.io/spec/1.0.0/errors/validation"
    do:
      # Handle validation errors
  - error:
      # Catch all other errors
    do:
      # Generic error handling
```

### Using Error Information

Access error details using the `as` property:

```yaml
- validateTask:
    try:
      do:
        - validateData:
            callHTTP:
              url: "http://validator/check"
              method: "POST"
              body: "${ .payload }"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/validation"
            as: "validationError"
          do:
            - logValidationError:
                set:
                  errorDetails: "${ .validationError.details }"
                  errorField: "${ .validationError.instance }"
```

In this example, the caught error is stored in the `validationError` variable, which can be used in subsequent tasks.

## Conditional Error Handling

Use the `when` property to conditionally handle errors based on their properties:

```yaml
catch:
  - error:
      with:
        type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
      when: "${ .status == 429 }"
    do:
      # Handle rate limiting errors
  - error:
      with:
        type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication"
      when: "${ .status == 503 }"
    do:
      # Handle service unavailable errors
```

## Error Propagation

If an error isn't caught by any catch block, it propagates up the workflow hierarchy:

```yaml
- outerTask:
    try:
      do:
        - innerTask:
            try:
              do:
                - riskyOperation:
                    # ...
              catch:
                - error:
                    with:
                      type: "https://serverlessworkflow.io/spec/1.0.0/errors/timeout"
                  do:
                    # ...
      catch:
        # This will catch any errors not caught by innerTask
        - error:
            do:
              # ...
```

## Best Practices

1. **Be specific with error types**: Catch the most specific error types first.
2. **Provide meaningful fallbacks**: Always provide sensible default behavior.
3. **Avoid swallowing errors**: Log error details for debugging purposes.
4. **Structure error handling hierarchically**: Catch specific errors in inner blocks, and use outer blocks for generic handling.
5. **Combine with retries**: For transient errors, use retry policies before resorting to fallbacks.

## Related Resources

- [Retry Mechanism](lemline-howto-retry.md)
- [Custom Error Types](lemline-howto-custom-errors.md)
- [Debugging Workflows](lemline-howto-debug.md)
- [Resilience Patterns](dsl-resilience-patterns.md)