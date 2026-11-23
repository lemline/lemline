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
