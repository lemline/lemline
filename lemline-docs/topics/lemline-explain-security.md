# Understanding Security in Lemline

This document explains which security controls Lemline provides today: how workflows authenticate to the services they call, how they use secrets, how inputs and outputs are validated, and how the gRPC gateway is protected.

## Security Boundaries

Lemline has three places where security matters:

1. **Outgoing calls made by workflows**: authentication is declared in the workflow definition.
2. **Secrets used by workflows**: values come from the runner environment; `$secrets` itself is never written to messages or the database, but data derived from it can be (see below).
3. **The gRPC gateway**: the entry point used by clients and the dashboard, protected by TLS and JWT.

Runners themselves expose no API: they only consume from the message broker and the database, so their protection is the protection of those systems (network access, credentials, TLS settings of the broker and database).

## Authentication of Outgoing Calls

### Supported Authentication Methods

| Method | Status | Applies to |
|--------|--------|------------|
| Basic | Supported | `call: http` |
| Bearer | Supported | `call: http` |
| OAuth 2.0 | Not yet supported: a call using it fails with a `configuration` error | - |
| OpenID Connect, Digest, Certificate | Not supported | - |

### Authentication Configuration

Declare an authentication inline on the endpoint:

<code-block lang="yaml" src="workflows/lemline-explain-security/authentication-configuration.yaml" include-lines="6-18"/>

Or declare it once in `use.authentications` and reference it by name:

<code-block lang="yaml" src="workflows/lemline-explain-security/authentication-configuration-2.yaml" include-lines="6-23"/>

A reference to an unknown name fails with a `configuration` error.

## Secret Management

Workflows declare the secrets they need in `use.secrets` and read them in expressions through `$secrets.<name>`. The runner reads each declared name from the environment variable with exactly the same name, before each step it executes.

- **Not persisted by themselves**: the `$secrets` values are resolved on the runner for each step and are not part of broker messages or stored workflow state. Any value derived from them that ends up in task output, context or workflow output is stored like other workflow data. With `output: response`, an HTTP task's output includes the headers of the request it sent, including `Authorization`: filter them out (for example with `output.as`) before they become workflow data.
- **Scoped**: a workflow only sees the secrets it declares; a child workflow resolves its own.
- **Rotation**: update the variable and restart the runners; instances in progress use the new value from their next step.
- **Missing value**: the workflow faults with an `authorization` error.

Lemline has no built-in secret store: expose Vault, cloud secret managers or Kubernetes Secrets to the runners as environment variables. See [Managing Secrets in Lemline](lemline-howto-secrets.md).


## Data Validation

### Input and Output Schemas

Validate data with JSON Schema at the workflow or task level:

<code-block lang="yaml" src="workflows/reference/order-input-schema.yaml" include-lines="6-14"/>

Lemline checks `input.schema` before a task runs, `output.schema` after it runs and `export.schema` on exported data. Data that does not match raises a `validation` error, which can be caught with `try`.

### Logs

Workflow inputs and outputs can appear in logs at `DEBUG` level. Keep production runners at `INFO` or above, and never put secret values in workflow inputs.

## Gateway Security

The gRPC gateway (`lemline gateway start`) is the only network API Lemline exposes.

- **TLS** is on by default (`lemline.gateway.tls.enabled=true`) and needs a certificate and private key.
- **Client certificates** (mTLS) are controlled by `lemline.gateway.tls.client-auth`: `none`, `request` or `required`.
- **JWT authentication** is on by default. Tokens are verified against `lemline.gateway.authentication.jwt.issuer` and `lemline.gateway.authentication.jwt.jwks-url`. Authentication requires TLS.
- **Scopes**: starting an instance needs the `lemline.start` scope, watch streams need `lemline.watch`, and reading the gateway configuration needs `lemline.config`.
- **Namespaces**: the `lemline_namespaces` claim lists the namespaces a caller may access; `*` allows all.

When authentication is disabled, every caller can reach every namespace. Only do this on a trusted network.

See [gRPC Gateway Reference](lemline-ref-gateway.md) for every setting.

## Namespace Isolation

A runner only consumes and executes the namespaces listed in `lemline.namespaces`. Use separate namespaces, and separate broker and database credentials if needed, to keep teams or environments apart.

## Security Best Practices

### Authentication Best Practices

1. **Never write credentials in workflow files**: read them from `$secrets`.
2. **Prefer short-lived tokens** for bearer authentication.
3. **Use HTTPS endpoints** for every authenticated call.

### Secret Management Best Practices

1. **No Hardcoded Secrets**: Never hardcode secrets in workflows
2. **Use Secret References**: Always use secret references
3. **Centralized Secret Store**: Use a dedicated secret management system
4. **Secret Rotation**: Implement a secret rotation strategy
5. **Audit Secret Access**: Monitor and log secret access

### Deployment Best Practices

1. **Keep gateway TLS and authentication enabled** outside local development.
2. **Restrict broker and database access** to runners and the gateway.
3. **Pass passwords as environment variables**, not in the configuration file.
4. **Grant the narrowest scopes and namespaces** in gateway tokens.

## Security Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| HTTP call fails with an `authorization` error | A secret declared in `use.secrets` has no matching environment variable on the runner |
| HTTP call fails with a `configuration` error mentioning authentication | Missing username, password or token, unknown authentication name, or OAuth 2.0 used |
| Gateway returns `UNAUTHENTICATED` | Missing or invalid bearer token |
| Gateway returns `PERMISSION_DENIED` | Token lacks the required scope or namespace |

Run the runner with `--debug` to see the details of a failing call. Do not leave debug logging on in production: it can log request and response data.

## Related Resources

- [Authentication Reference](lemline-ref-auth.md)
- [Secrets Management](lemline-howto-secrets.md)
- [gRPC Gateway Reference](lemline-ref-gateway.md)
- [Runner Configuration Reference](lemline-ref-config.md)
