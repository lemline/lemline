# Retrying failed workflow tasks

Configure retries under `catch.retry` on a `try` task. Use `delay` for the initial
interval and `limit.attempt.count` to bound attempts. With a backoff configured,
the runner derives subsequent intervals from the configured strategy.

## Reuse a retry policy

Put a named policy under `use.retries` and reference it from `catch.retry`. In this example,
the catch block handles the HTTP 503 response. Lemline reports a failed HTTP call as a `communication`
error whose `status` is the HTTP status, so the catch filters on that type and status. If retries are exhausted, the catch task list returns
a fallback result.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

### Run the example

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The HTTP
examples call the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop); start it from the
root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Save the definition as `retry-and-fallback.yaml`. The `failures` input makes the shop answer HTTP 503 that many
times before it returns the stock; `key` counts the failures per instance.

```bash
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":2}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
```

The first instance fails twice, is retried after about 2 and then 4 seconds, and returns the stock. The second fails
on its call and on its three retries, then returns `{"inventory":[],"stale":true}`. The worker picks up delayed
retries every 10 seconds, so each retry can take up to 10 seconds longer than its delay; allow about a minute. The
shop's `failures` list shows `failed: 2, passed: 1` and `failed: 4, passed: 0` for the two instances.

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

To use the same policy in one place, put the policy object directly under `catch.retry`.
Use the `when` or `exceptWhen` predicates to limit retries further. See the
[Try Task reference](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#try) for error matching and fallback behavior.
