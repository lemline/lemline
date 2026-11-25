# How to use API keys

Declare the key in `use.secrets` and supply its value through your
[Lemline secrets configuration](lemline-howto-secrets.md).

## Prepare the examples

Complete [the SQLite setup](lemline-tutorial-setup.md). The examples call the partner route of the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop), `GET /partner/stock`. It answers
HTTP 401 without credentials and HTTP 403 with a wrong value, and lists every request it received, with its headers
and query, under **Requests** on the shop page. Start it from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Run the other commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.
The worker reads secrets from its environment when it starts, so each example below stops the worker with Ctrl+C,
exports the value, and starts the worker again in the same terminal. Run the `definition` and `instance` commands
from a second terminal.

Export the shop's demo values, `demo-api-key` as `apiKey` and `demo-token` as `apiToken`, and start the worker:

```bash
export apiKey='demo-api-key'
export apiToken='demo-token'
bin/lemline listen --debug
```

## Send a key in a header

Set the header required by your service in `with.headers`. The expression resolves the
secret when the task runs.

<code-block lang="yaml" src="workflows/reference/http-request-with-api-key.yaml"/>

Save it as `http-request-with-api-key.yaml`:

```bash
bin/lemline definition post -f http-request-with-api-key.yaml
bin/lemline instance start examples http-request-with-api-key
```

Expect `{"sku":"SKU-1","available":100,"authenticatedWith":"api-key-header"}`. The shop lists the `x-api-key` header it received.

## Send a key in a query parameter

Use `with.query` if the service requires a query parameter. Headers are preferable where
the service supports them because URLs often appear in access logs.

<code-block lang="yaml" src="workflows/reference/http-request-with-query-key.yaml"/>

Save it as `http-request-with-query-key.yaml`:

```bash
bin/lemline definition post -f http-request-with-query-key.yaml
bin/lemline instance start examples http-request-with-query-key
```

Expect `{"sku":"SKU-1","available":100,"authenticatedWith":"api-key-query"}`. The shop lists the request with `api_key=demo-api-key` in its query: the key also appears in the worker log line of the request URL.

## Send a bearer token

For an API that expects `Authorization: Bearer ...`, use a named bearer authentication policy
and reference it from `with.endpoint.authentication.use`.

<code-block lang="yaml" src="workflows/reference/bearer-authentication.yaml"/>

Save it as `bearer-authentication.yaml`:

```bash
bin/lemline definition post -f bearer-authentication.yaml
bin/lemline instance start examples bearer-authentication
```

Expect `{"sku":"SKU-1","available":100,"authenticatedWith":"bearer"}`. The shop lists the `authorization` header `Bearer demo-token`.

A missing or wrong value is the service's answer, not Lemline's. Stop the worker, export a wrong key, start the
worker again and repeat the first example:

```bash
export apiKey='wrong-key'
bin/lemline listen --debug
```

```bash
bin/lemline instance start examples http-request-with-api-key
```

The shop answers HTTP 403 and the instance faults with a `communication` error, status 403, titled
`HTTP 403: {"error":"Invalid credentials for api-key-header"`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See the [Authentication Reference](lemline-ref-auth.md) for policy placement and secret reuse.
