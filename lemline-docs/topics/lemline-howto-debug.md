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

The quickest way to see what a runner does is to start it with a more verbose log level:

```bash
lemline listen --debug
```

The `--debug`, `--info`, `--warn` and `--error` options work with every command; the default level is `ERROR`. At `DEBUG` level, the runner logs each task it enters with its input, and the workflow output.

Quarkus log properties are not read from the Lemline configuration file, which only accepts `lemline.*` settings. Set them as environment variables instead (or as `-D` system properties):

```bash
export QUARKUS_LOG_LEVEL=INFO
export QUARKUS_LOG_CATEGORY__COM_LEMLINE_CORE__LEVEL=DEBUG
export QUARKUS_LOG_CATEGORY__COM_LEMLINE_CORE_EXPRESSIONS__LEVEL=TRACE
```

Log levels include:

- **ERROR**: Severe issues that prevent workflow execution
- **WARN**: Issues that don't prevent execution but might cause incorrect behavior
- **INFO**: Normal workflow execution events
- **DEBUG**: Detailed information for diagnosing problems
- **TRACE**: Very detailed information, including expression evaluation steps

## Inspecting Workflow Definitions

Print the definition Lemline has stored for a workflow:

```bash
lemline definition get <namespace> <name> <version>
```

Without arguments, `lemline definition get` lists the registered definitions and lets you pick one.

## Following Workflow Execution

To follow an instance step by step, run the runner at `DEBUG` level (see above) and filter the logs on the instance id: every log line carries the workflow namespace, name, version and instance id. The dashboard, served through the gRPC gateway, shows instances and their lifecycle events.

## Debugging Expressions

JQ expressions are a common source of workflow issues. Lemline has no command to evaluate an expression on its own: test it with the `jq` command-line tool against sample data, then run the workflow with `--debug` to see the actual input of each task.

```bash
echo '{"user":{"address":{"city":"Berlin"}}}' | jq '.user.address.city'
```

## Debugging Common Issues

### Schema Validation Errors

If your workflow fails with schema validation errors:

1. Check the error message for specific field issues
2. Validate your YAML against the workflow schema
3. Register the file again: `lemline definition post` parses each file and reports the files it cannot read:

```bash
lemline definition post -f path/to/workflow.yaml
```

### Expression Evaluation Errors

Common expression issues include:

1. **Path not found**: Check that the referenced property exists
   Run the runner with `--debug` to see the input of the task where the expression fails.

2. **Type errors**: Ensure the data type matches the expected type
   ```yaml
   # Add type conversion in your expression
   "${ number(.amount) * 0.1 }"
   ```

3. **Array access issues**: Check array indices and iterations
   ```bash
   # Test array access
   echo '{"items":[{"name":"Product"}]}' | jq '.items[0].name'
   ```

### HTTP Call Problems

Debug HTTP task issues:

1. Run the runner with `--debug`
2. Check request details in logs
3. Verify endpoint, method, and authentication
4. Examine response codes and bodies

### Event Handling Issues

When debugging event-based tasks:

1. Check event broker connectivity
2. Verify event topic/queue names
3. Ensure event correlations match
4. Set the `com.lemline.runner.messaging` log category to `DEBUG`

## Testing with Mocked Activities

To run workflows without calling real services, start a runner in test mode with a file of mocked activity responses:

```bash
lemline listen --mock-config mocks.yaml
```

## Messages That Could Not Be Processed

Messages that a runner receives for a namespace it does not serve are parked in the failures table. Once the right runner is deployed, re-send them:

```bash
lemline failures replay --dry-run
lemline failures replay --yes
```

## Debugging Timeouts and Performance

To troubleshoot performance issues:

1. Read the runner metrics at `http://<host>:8080/q/metrics` (message processing counts, failures and durations per channel)
2. Look for instances that stay pending in the dashboard
3. Remember that only `listen` task timeouts are enforced; HTTP calls use fixed limits (15 seconds to connect, 30 seconds per request)

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
