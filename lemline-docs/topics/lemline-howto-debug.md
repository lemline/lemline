# Debugging Lemline Workflows

This guide provides techniques and best practices for debugging workflows when they don't behave as expected.

## Understanding Workflow Execution

Before diving into debugging techniques, it's important to understand how Lemline executes workflows:

1. Workflows are parsed and validated against the schema
2. Nodes are constructed and linked in a graph structure
3. Execution progresses through nodes, evaluating expressions and performing actions
4. States and variables are tracked throughout execution
5. Errors can occur at any stage and are handled according to your error handling configuration

## Enabling Debug Logging

Lemline provides comprehensive logging to help diagnose issues. Configure your logging levels in `application.properties`:

```properties
# Enable detailed logging for core components
quarkus.log.category."com.lemline.core".level=DEBUG
quarkus.log.category."com.lemline.core.expressions".level=TRACE
quarkus.log.category."com.lemline.core.nodes".level=DEBUG

# Logging for specific subsystems
quarkus.log.category."com.lemline.runner.outbox".level=DEBUG
quarkus.log.category."com.lemline.runner.messaging".level=DEBUG
```

Log levels include:
- **ERROR**: Severe issues that prevent workflow execution
- **WARN**: Issues that don't prevent execution but might cause incorrect behavior
- **INFO**: Normal workflow execution events
- **DEBUG**: Detailed information for diagnosing problems
- **TRACE**: Very detailed information, including expression evaluation steps

## Inspecting Workflow Definitions

Inspect how Lemline interprets your workflow definition:

```bash
lemline definitions get my-workflow --verbose
```

This command displays:
- Parsed workflow structure
- Validation results
- Node graph construction

## Tracing Workflow Execution

Enable execution tracing to see each step of the workflow:

```properties
lemline.tracing.enabled=true
lemline.tracing.level=DETAILED
```

Trace levels include:
- **BASIC**: Shows only task transitions
- **STANDARD**: Shows tasks, state changes, and errors
- **DETAILED**: Shows all operations including expression evaluation results

View traces using the CLI:

```bash
lemline instances traces get <instance-id>
```

## Debugging Expressions

JQ expressions are a common source of workflow issues. Debug them using:

### Expression Testing Command

Test expressions against sample data:

```bash
lemline debug expression '.user.address.city' '{"user":{"address":{"city":"Berlin"}}}'
```

### Trace Expression Evaluation

Enable expression tracing:

```properties
lemline.expressions.trace=true
```

This shows step-by-step evaluation of complex expressions.

## Debugging Common Issues

### Schema Validation Errors

If your workflow fails with schema validation errors:

1. Check the error message for specific field issues
2. Validate your YAML against the workflow schema
3. Use the `lemline definitions validate` command:

```bash
lemline definitions validate path/to/workflow.yaml
```

### Expression Evaluation Errors

Common expression issues include:

1. **Path not found**: Check that the referenced property exists
   ```bash
   # Debug by examining the state at that point
   lemline instances states get <instance-id> <node-position>
   ```

2. **Type errors**: Ensure the data type matches the expected type
   ```yaml
   # Add type conversion in your expression
   "${ number(.amount) * 0.1 }"
   ```

3. **Array access issues**: Check array indices and iterations
   ```bash
   # Test array access
   lemline debug expression '.items[0].name' '{"items":[{"name":"Product"}]}'
   ```

### HTTP Call Problems

Debug HTTP task issues:

1. Enable HTTP request/response logging:
   ```properties
   lemline.http.trace=true
   ```

2. Check request details in logs
3. Verify endpoint, method, and authentication
4. Examine response codes and bodies

### Event Handling Issues

When debugging event-based tasks:

1. Check event broker connectivity
2. Verify event topic/queue names
3. Ensure event correlations match
4. Use message tracing:
   ```properties
   lemline.messaging.trace=true
   ```

## Using the CLI for Debugging

The CLI provides several debugging commands:

```bash
# List running instances
lemline instances list

# Get instance details
lemline instances get <instance-id>

# View instance state
lemline instances states get <instance-id>

# Get execution history
lemline instances history <instance-id>

# Examine specific node state
lemline instances states get <instance-id> <node-position>
```

## Setting Breakpoints

You can define workflow-level breakpoints to pause execution at specific points:

```yaml
- debugCheckpoint:
    extension:
      breakpoint: true
    do:
      - continueExecution:
          # Next steps
```

Execution will pause at this point, allowing you to inspect state before continuing.

## Debugging Timeouts and Performance

To troubleshoot performance issues:

1. Enable performance metrics:
   ```properties
   lemline.metrics.enabled=true
   ```

2. View metrics for task durations, retries, and more
3. Check for stuck workflows:
   ```bash
   lemline admin list-stuck-instances
   ```

4. Adjust timeout configurations as needed

## Best Practices

1. **Start simple**: Begin with minimal workflows and add complexity gradually
2. **Test in isolation**: Debug individual tasks before combining them
3. **Use meaningful task names**: Clear naming makes debugging easier
4. **Add diagnostic tasks**: Insert logging tasks at key points
5. **Check data shapes**: Verify the structure of your data at each step
6. **Review error handling**: Ensure proper try/catch blocks are in place
7. **Use mock services**: Test with predictable external services first

## Related Resources

- [Error Handling with Try-Catch](lemline-howto-try-catch.md)
- [Implementing Retry Mechanisms](lemline-howto-retry.md)
- [Custom Error Types](lemline-howto-custom-errors.md)
- [Workflow Monitoring](lemline-howto-monitor.md)