# How to use API keys

Declare the key in `use.secrets` and supply its value through your
[Lemline secrets configuration](lemline-howto-secrets.md).

## Send a key in a header

Set the header required by your service in `with.headers`. The expression resolves the
secret when the task runs.

<code-block lang="yaml" src="workflows/reference/http-request-with-api-key.yaml"/>

## Send a key in a query parameter

Use `with.query` if the service requires a query parameter. Headers are preferable where
the service supports them because URLs often appear in access logs.

<code-block lang="yaml" src="workflows/reference/http-request-with-query-key.yaml"/>

## Send a bearer token

For an API that expects `Authorization: Bearer ...`, use a named bearer authentication policy
and reference it from `with.endpoint.authentication.use`.

<code-block lang="yaml" src="workflows/reference/bearer-authentication.yaml"/>

See the [Authentication Reference](lemline-ref-auth.md) for policy placement and secret reuse.
