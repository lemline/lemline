# Authentication Reference

Lemline applies Basic and Bearer authentication to HTTP tasks. Define reusable policies by name under `use.authentications` and select one with `with.endpoint.authentication.use`. Declare the secret names under `use.secrets` and supply their values through the worker's secret configuration.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: read-profile
  version: '1.0.0'
use:
  secrets: [profileToken]
  authentications:
    profileApi:
      bearer:
        token: ${ $secrets.profileToken }
do:
  - getProfile:
      call: http
      with:
        method: get
        endpoint:
          uri: https://profiles.example.com/me
          authentication: { use: profileApi }
```

A JWT issued by an external identity provider can be passed as the Bearer token. For Basic authentication, supply both the username and password.

```yaml
use:
  secrets: [servicePassword]
  authentications:
    serviceAccount:
      basic:
        username: orders-reader
        password: ${ $secrets.servicePassword }
```

## OAuth, OpenID Connect and digest

The Java SDK schema also accepts `oauth2`, `oidc` and `digest` policies. HTTP calls using these policies currently fail with a configuration error in Lemline. For a runnable OAuth integration, obtain a token from the identity provider and pass it to the protected service as a Bearer token.

```yaml
use:
  secrets: [clientSecret]
  authentications:
    serviceOAuth:
      oauth2:
        authority: https://auth.example.com
        grant: client_credentials
        client:
          id: orders-reader
          secret: ${ $secrets.clientSecret }
        scopes: [orders.read]
```

## Provider-specific authentication

For AWS SigV4, HMAC signing, mutual TLS or a provider-specific token exchange, configure a service or client that implements the required protocol. The SDK 1.0.3 authentication policy has no fields for custom signers, certificate policies, token caches or Java provider classes.

See [API keys](lemline-howto-api-keys.md), [secrets](dsl-secrets.md) and [runtime support by feature](lemline-ref-supported-features.md).
