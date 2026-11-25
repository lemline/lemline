# Authentication Reference

Authentication policies are part of the Open Workflow DSL. Lemline supports basic and
bearer authentication for its HTTP executor. See [Supported Features](lemline-ref-supported-features.md)
for runtime support by protocol and authentication type.

## Policy placement

Define a named policy under `use.authentications` and reference it from the endpoint
with `authentication.use`. Declare referenced secret names under `use.secrets`.

<code-block lang="yaml" src="workflows/reference/bearer-authentication.yaml"/>

## Inline basic authentication

Alternatively, configure `authentication.basic` directly on the endpoint. The
`username` and `password` fields supply the credentials.

<code-block lang="yaml" src="workflows/dsl-authentication/inline-authentication.yaml"/>

## API keys and OAuth 2.0

For a service-specific API key, see [How to Use API Keys](lemline-howto-api-keys.md)
for request headers and query parameters. The [OAuth 2.0 guide](lemline-howto-oauth2.md)
shows the specification's client credentials configuration and describes the runtime limitation.

Use the [Secrets Reference](lemline-ref-secrets.md) to configure secret values outside
the document.

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The examples read secrets from the worker's environment. Stop the setup worker with Ctrl+C, export the values, and start it again:

```bash
export apiToken='demo-token'
export tempPassword='demo-password'
bin/lemline listen --debug
```

Run the next commands from a second terminal in the setup directory.

```bash
bin/lemline definition post -f bearer-authentication.yaml
bin/lemline instance start examples bearer-authentication
bin/lemline definition post -f inline-authentication.yaml
bin/lemline instance start examples inline-authentication
```

| Example | Expected result |
|---|---|
| `bearer-authentication` | The partner stock with `"authenticatedWith":"bearer"` |
| `inline-authentication` | The partner stock with `"authenticatedWith":"basic"` |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

