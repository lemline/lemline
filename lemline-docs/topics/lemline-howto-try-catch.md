# Error Handling with Try-Catch

A `try` task runs a list of tasks. If a task fails, `catch` can filter the error, retry the
try block, or execute a fallback list.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The HTTP
examples call the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop); start it from the
root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

## Filter an error and retry

Set `catch.errors.with` to match predefined fields such as the error type or status.
Use `catch.when` for a jq predicate over the error when the field filter is insufficient. A failed HTTP call needs
`when` on the current build: it is a `runtime` error with status 500 whose title starts with the HTTP status
([issue 97](https://github.com/lemline/lemline/issues/97)).

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

Save it as `retry-and-fallback.yaml`. The `failures` input makes the shop answer HTTP 503 that many times:

```bash
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":1}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
```

The first instance returns the stock after one retry. The second fails four times and returns
`{"inventory":[],"stale":true}`. Allow about a minute: the worker picks up delayed retries every 10 seconds.

## Read the caught error

The caught error is available as `$error` by default. Use `catch.as` to choose another
name. The following example reads error fields inside the catch task list.

<code-block lang="yaml" src="workflows/reference/format-error-result.yaml"/>

Save it as `format-error-result.yaml`. Send one valid reservation and two the shop refuses:

```bash
bin/lemline definition post -f format-error-result.yaml
bin/lemline instance start examples format-error-result --input '{"orderId":"ORD-901","items":[{"id":"SKU-1","quantity":1}]}'
bin/lemline instance start examples format-error-result --input '{"orderId":"ORD-902","items":[{"id":"SKU-9","quantity":1}]}'
bin/lemline instance start examples format-error-result --input '{"orderId":"ORD-903","items":[{"id":"SKU-1","quantity":500}]}'
```

The first instance returns the reservation. The shop answers HTTP 400 to the second and HTTP 409 to the third; both
complete with `success: false`, `status: 500`, the `runtime` error type, and a `reason` that starts with
`HTTP 400: {"error":"Invalid items"` and `HTTP 409: {"error":"Out of stock"` respectively. On the current build a
failed HTTP call is always a `runtime` error with status 500, and the HTTP status is only in the title
([issue 97](https://github.com/lemline/lemline/issues/97)).

## Propagate failures

Errors that do not match the filter propagate to the surrounding task scope. A
fallback can also raise an error if the workflow should fault after cleanup.

<code-block lang="yaml" src="workflows/reference/compensate-reservation.yaml"/>

Save it as `compensate-reservation.yaml` and send a declined payment:

```bash
bin/lemline definition post -f compensate-reservation.yaml
bin/lemline instance start examples compensate-reservation --input '{"orderId":"ORD-904","items":[{"id":"SKU-1","quantity":2}],"paymentInfo":{"token":"declined"}}'
```

The charge fails with HTTP 402, the catch releases the reservation, then raises `Order completion failed`: the
instance faults with that title and status 502, and the shop shows the `ORD-904` reservation as `released`.

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See [Retry Mechanism](lemline-howto-retry.md) and [Custom Errors](lemline-howto-custom-errors.md).

Run the [shop checkout tutorial](lemline-tutorial-order-processing.md) to observe the release before the workflow faults.
