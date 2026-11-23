# Configuring OAuth 2.0 Authentication

The Open Workflow DSL can describe an OAuth 2.0 client credentials grant. Lemline can
load the definition, while its HTTP executor currently supports basic and bearer
authentication. See [Supported Features](lemline-ref-supported-features.md).

## Client credentials grant

Place the `oauth2` authentication policy on the endpoint, with the authority, grant and
client configuration. The token endpoint can be specified under `endpoints`.

<code-block lang="yaml" src="workflows/lemline-examples-integrations/http-with-authentication.yaml"/>

Store the client secret outside the definition and inject it through a declared workflow
secret when using an OAuth-capable executor. To call an HTTP service from Lemline with an
existing token, use [bearer authentication](lemline-ref-auth.md).
