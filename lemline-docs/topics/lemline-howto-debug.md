# Debugging Lemline Workflows

This guide provides techniques and best practices for debugging workflows when they don't behave as expected.

## Understanding Workflow Execution

Before diving into debugging techniques, it's important to understand how Lemline executes workflows:

1. Workflows are parsed and validated against the schema
2. Nodes are constructed and linked in a graph structure
3. Execution progresses through nodes, evaluating expressions and performing actions
4. States and variables are tracked throughout execution
5. Errors can occur at any stage and are handled according to your error handling configuration

## Follow Along

The commands below run on [the SQLite setup](lemline-tutorial-setup.md). Run them from the setup directory. With the
JAR, substitute `java -jar lemline.jar` for `bin/lemline`. Some sections use the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop); start it from the root of the Lemline
source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

## Enabling Debug Logging

The quickest way to see what a runner does is to start it with a more verbose log level:

```bash
bin/lemline listen --debug
```

The `--debug`, `--info`, `--warn` and `--error` options work with every command; the default level is `ERROR`. At `DEBUG` level, the runner logs each task it enters with its input, and the workflow output.

Quarkus log properties are not read from the Lemline configuration file, which only accepts `lemline.*` settings. Set them as environment variables instead (or as `-D` system properties):

```bash
export QUARKUS_LOG_LEVEL=INFO
export QUARKUS_LOG_CONSOLE_LEVEL=DEBUG   # the console only shows ERROR by default
export QUARKUS_LOG_CATEGORY__COM_LEMLINE_CORE__LEVEL=DEBUG
```

Log levels include:

- **ERROR**: Severe issues that prevent workflow execution
- **WARN**: Issues that don't prevent execution but might cause incorrect behavior
- **INFO**: Normal workflow execution events
- **DEBUG**: Detailed information for diagnosing problems
- **TRACE**: not available in the released binaries, which are built with `DEBUG` as the lowest level

## Inspecting Workflow Definitions

Print the definition Lemline has stored for a workflow. Save this definition as `hello.yaml` and register it:

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

```bash
bin/lemline definition post -f hello.yaml
bin/lemline definition get examples shop-receipt 0.1.0
```

The second command prints the stored YAML. Without arguments, `bin/lemline definition get` lists the registered
definitions and lets you pick one.

## Following Workflow Execution

To follow an instance step by step, run the runner at `DEBUG` level (see above) and filter the logs on the instance id: every log line carries the workflow namespace, name, version and instance id. The dashboard, served through the gRPC gateway, shows instances and their lifecycle events.

## Debugging Expressions

JQ expressions are a common source of workflow issues. Lemline has no command to evaluate an expression on its own: test it with the `jq` command-line tool against sample data, then run the workflow with `--debug` to see the actual input of each task.

```bash
echo '{"user":{"address":{"city":"Berlin"}}}' | jq '.user.address.city'
```

For example, this workflow sums order lines. Save it as `aggregate-order-data.yaml`:

<code-block lang="yaml" src="workflows/reference/aggregate-order-data.yaml"/>

```bash
bin/lemline definition post -f aggregate-order-data.yaml
bin/lemline instance start examples aggregate-order-data --input '{"orderId":"ORD-1"}'
```

The input has no `items`, so `.items[]` fails: the instance faults with an `expression` error (status 400) whose
title shows the input and the expression, `Unable to evaluate content {"orderId":"ORD-1"} using expr [.items[] | …`. With `--debug`, the worker log shows the task input `{"orderId":"ORD-1"}` just before the error.

## Debugging Common Issues

### Schema Validation Errors

If your workflow fails with schema validation errors:

1. Check the error message for specific field issues
2. Validate your YAML against the workflow schema
3. Register the file again: `lemline definition post` parses each file and reports the files it cannot read:

```bash
bin/lemline definition post -f hello.yaml
```

### Expression Evaluation Errors

Common expression issues include:

1. **Path not found**: Check that the referenced property exists
   Run the runner with `--debug` to see the input of the task where the expression fails.

2. **Type errors**: Ensure the data type matches the expected type
   ```jq
   (.amount | tonumber) * 0.1
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

To run workflows without calling real services, start a runner in test mode with a file of mocked activity
responses. Each `http` rule matches a URL (a `*` wildcard is allowed) and a method, and returns the given body.
The file can be YAML or JSON. Create `mocks.json` with a rule for the shop's stock:

```bash
cat > mocks.json <<'EOF'
{
  "http": [
    {
      "match": { "url": "http://127.0.0.1:8787/stock", "method": "GET" },
      "response": { "status": 200, "body": { "sku": "SKU-1", "available": 0, "reserved": 0 } }
    }
  ]
}
EOF
```

Stop the running worker with Ctrl+C, start it in test mode, then run a workflow that reads the stock. Save the
workflow as `check-stock.yaml`:

<code-block lang="yaml" src="workflows/lemline-getting-started/connect-to-http-services.yaml"/>

```bash
bin/lemline listen --debug --mock-config mocks.json
```

From a second terminal:

```bash
bin/lemline definition post -f check-stock.yaml
bin/lemline instance start examples check-stock
```

The instance completes with the mocked body, `{"sku":"SKU-1","available":0,"reserved":0}`, while the real shop still
has 100 units. A mocked call never reaches the shop. Mocked HTTP responses are returned whatever their `status`;
use `response.error` to make a mocked call fail. Restart the worker without `--mock-config` when you are done.

## Messages That Could Not Be Processed

With a shared broker, messages that a runner receives for a namespace it does not serve are parked in the failures
table. Once the right runner is deployed, re-send them:

```bash
bin/lemline failures replay --dry-run
bin/lemline failures replay --yes
```

On the SQLite setup nothing is parked: the message waits in the queue until a worker serving its namespace starts,
and `failures replay --dry-run` answers `No failures found with reason 'foreign_namespace'`.

## Debugging Timeouts and Performance

To troubleshoot performance issues:

1. Read the runner metrics at `http://<host>:8080/q/metrics` (message processing counts, failures and durations per channel)
2. Look for instances that stay pending in the dashboard
3. Remember that only `listen` task timeouts are enforced; HTTP calls use fixed limits (15 seconds to connect, 30 seconds per request)

To see the request limit, this workflow asks the shop to wait before answering. If the worker still runs with
`--mock-config`, stop it with Ctrl+C and start it again without that option:

```bash
bin/lemline listen --debug
```

Save the workflow as `slow-http-call.yaml`:

<code-block lang="yaml" src="workflows/reference/slow-http-call.yaml"/>

```bash
bin/lemline definition post -f slow-http-call.yaml
bin/lemline instance start examples slow-http-call --input '{"delayMs":1000}'
bin/lemline instance start examples slow-http-call --input '{"delayMs":40000}'
```

The first instance returns the stock after one second. The second faults after 30 seconds, before the shop
answers, with a `communication` error titled `Socket timeout has expired [url=http://127.0.0.1:8787/stock?delayMs=40000,
socket_timeout=30000] ms`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

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
