# Configuring OAuth 2.0 Authentication

A workflow can declare an OAuth policy in the Java SDK's 1.0.3 schema. Lemline currently applies Basic and Bearer policies to HTTP requests. A request that selects an OAuth or OpenID Connect policy fails with a configuration error.

## Declare a client-credentials policy

The authority identifies the authorization server. If its token endpoint uses a custom path, specify the relative path under `endpoints.token`. Supply the client secret through the worker's secrets configuration.

```yaml
use:
  secrets: [clientSecret]
  authentications:
    serviceAuth:
      oauth2:
        authority: https://auth.example.com
        grant: client_credentials
        endpoints:
          token: /oauth/token
        client:
          id: inventory-reader
          secret: ${ $secrets.clientSecret }
        scopes: [inventory.read]
do:
  - getInventory:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/inventory
          authentication: { use: serviceAuth }
```

## Use an externally issued token

For execution on Lemline, arrange for the identity provider or deployment environment to supply a valid access token to the worker. The following example uses that token directly. Refresh the token through the issuing system before it expires.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: get-inventory
  version: '1.0.0'
use:
  secrets: [inventoryAccessToken]
  authentications:
    inventoryApi:
      bearer:
        token: ${ $secrets.inventoryAccessToken }
do:
  - getInventory:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/inventory
          authentication: { use: inventoryApi }
```

See [Authentication Reference](lemline-ref-auth.md) and [Secrets](dsl-secrets.md).
