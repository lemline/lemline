# Defining and using custom errors

Workflow errors carry a URI identifying the error type and a numeric status. Optional
fields include `title`, `detail` and `instance`.

## Define and raise a named error

Put reusable errors under `use.errors`. Reference a named definition with `raise.error`.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The HTTP
examples call the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop); start it from the
root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

<code-block lang="yaml" src="workflows/reference/typed-error.yaml"/>

Save it as `typed-error.yaml`:

```bash
bin/lemline definition post -f typed-error.yaml
bin/lemline instance start examples typed-error --input '{"orderId":"ORD-905"}'
bin/lemline instance start examples typed-error
```

The first instance completes with `{"status":"accepted"}`. The second faults with the named error: type
`https://errors.example.com/invalid-order`, status 422, title `Invalid order`.

## Construct an error with dynamic details

Use an inline error object when fields depend on the current task input.

<code-block lang="yaml" src="workflows/reference/raise-order-error.yaml"/>

Save it as `raise-order-error.yaml`:

```bash
bin/lemline definition post -f raise-order-error.yaml
bin/lemline instance start examples raise-order-error --input '{"stock":1,"quantity":3}'
bin/lemline instance start examples raise-order-error --input '{"stock":5,"quantity":3}'
```

The first instance faults with status 409, title `Insufficient stock` and detail `Requested 3; available 1`. The
second has enough stock: the `if` skips the raise and the instance completes with its input.

## Read or map a caught error

Inside `catch.do`, read the error variable when constructing a fallback result.
To handle only one error type, add `catch.errors.with.type` to the `catch`.

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

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See [Error Handling with Try-Catch](lemline-howto-try-catch.md) for filtering and retries.
