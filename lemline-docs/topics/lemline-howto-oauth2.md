# Configuring OAuth 2.0 Authentication

The Open Workflow DSL can describe an OAuth 2.0 client credentials grant. Lemline registers such a definition, but
its HTTP executor supports only basic and bearer authentication: an instance faults before sending any request. See
[Supported Features](lemline-ref-supported-features.md). Until OAuth 2.0 is supported, obtain the token outside the
workflow and send it with [bearer authentication](lemline-howto-api-keys.md#send-a-bearer-token).

## Client credentials grant

Place the `oauth2` authentication policy on the endpoint, with the authority, grant and
client configuration. The token endpoint can be specified under `endpoints`.

<code-block lang="yaml" src="workflows/lemline-examples-integrations/http-with-authentication.yaml"/>

The client secret stays outside the definition: the policy reads it from the declared `clientSecret` secret.

## See what Lemline does with it

Complete [the SQLite setup](lemline-tutorial-setup.md) and start the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source
checkout. The shop has no token endpoint; the request never gets that far. With the JAR, substitute
`java -jar lemline.jar` for `bin/lemline`.

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Stop the setup worker with Ctrl+C, export the secret, and start the worker again:

```bash
export clientSecret='demo-secret'
bin/lemline listen --debug
```

From a second terminal in the setup directory, save the definition as `http-with-authentication.yaml` and run it:

```bash
bin/lemline definition post -f http-with-authentication.yaml
bin/lemline instance start examples http-with-oauth2
```

The definition registers. The instance faults with a `runtime` error titled `Unsupported OAuth2 authentication`, and
the shop receives no request. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
