# Connections Reference

This reference explains how workflows reuse endpoint settings in Lemline.

## Connections Overview

Lemline has no "connection" object: there is no `use.connections` section, no connection pools to configure, no connection health checks, failover or circuit breakers, and no `lemline.connections.*` runner settings.

Workflows reuse settings with the reusable components of the Open Workflow DSL that Lemline supports in the `use` section:

| `use` section | Reused in |
|---------------|-----------|
| `authentications` | `call: http` endpoints (`authentication.use`) |
| `secrets` | Expressions, through `$secrets` |
| `functions` | `call: <function name>` |
| `errors` | `raise` tasks (`error: <name>`) |
| `retries` | `try` / `catch` (`retry: <name>`) |

## Reusing an Endpoint's Authentication

<code-block lang="yaml" src="workflows/lemline-ref-connections/reusing-an-endpoint-s-authentication.yaml"/>

## Reusing a Call with a Function

Wrap a call in a function and call it by name. The `with` values of the calling task are the function's input:

<code-block lang="yaml" src="workflows/lemline-ref-connections/reusing-a-call-with-a-function.yaml"/>

## Reusing a Retry Policy

<code-block lang="yaml" src="workflows/lemline-ref-connections/reusing-a-retry-policy.yaml"/>

## Broker and Database Connections

The runner's own connections to the message broker and the database are set in the runner configuration, not in workflows. See [Runner Configuration Reference](lemline-ref-config.md) and [Message Brokers](lemline-howto-brokers.md).

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The examples read secrets from the worker's environment. Stop the setup worker with Ctrl+C, export the values, and start it again:

```bash
export USER_API_TOKEN='demo-token'
bin/lemline listen --debug
```

Run the next commands from a second terminal in the setup directory.

```bash
bin/lemline definition post -f reusing-an-endpoint-s-authentication.yaml
bin/lemline instance start examples reusing-an-endpoint-s-authentication
bin/lemline definition post -f reusing-a-call-with-a-function.yaml
bin/lemline instance start examples reusing-a-call-with-a-function --input '{"id":"C-200"}'
bin/lemline definition post -f reusing-a-retry-policy.yaml
bin/lemline instance start examples reusing-a-retry-policy --input '{"failures":1}'
```

| Example | Expected result |
|---|---|
| `reusing-an-endpoint-s-authentication` | The partner stock with `"authenticatedWith":"bearer"` |
| `reusing-a-call-with-a-function` | Customer C-200, Alan Turing |
| `reusing-a-retry-policy` | The stock after one retry |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Related Resources

- [HTTP Protocol Reference](lemline-ref-http.md)
- [Authentication Reference](lemline-ref-auth.md)
- [Runner Configuration Reference](lemline-ref-config.md)
