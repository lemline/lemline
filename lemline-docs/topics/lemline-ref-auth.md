# Authentication Reference

This reference documents all authentication methods supported by Lemline for securing API calls and service interactions.

## Authentication Overview

Lemline provides comprehensive authentication capabilities for:

1. **HTTP API calls**: `callHTTP` and `callOpenAPI` tasks
2. **gRPC service calls**: `callGRPC` tasks
3. **AsyncAPI message interactions**: `callAsyncAPI` tasks
4. **External resource access**: Scripts, containers, and functions

## Defining Authentication Configurations

Authentication configurations are defined in the `use.authentications` section of workflow definitions:

```yaml
use:
  authentications:
    - name: "serviceAuth"
      # Authentication configuration
```

Once defined, authentications can be referenced by name in tasks:

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      auth: "serviceAuth"  # Reference to authentication configuration
```

## Authentication Types

### Bearer Token Authentication

Provides authentication using a Bearer token in the HTTP Authorization header.

```yaml
use:
  authentications:
    - name: "tokenAuth"
      bearer:
        token: "your-static-token"
```

Using a secret for the token:

```yaml
bearer:
  token:
    secret: "api.token"  # Reference to a secret
```

Additional options:

```yaml
bearer:
  token:
    secret: "api.token"
  scheme: "Token"  # Custom scheme instead of "Bearer"
  in: "header"     # Where to include the token: "header" (default) or "query"
  name: "X-Auth-Token"  # Header or query parameter name
```

### Basic Authentication

Uses HTTP Basic Authentication with username and password.

```yaml
use:
  authentications:
    - name: "basicAuth"
      basic:
        username: "username"
        password:
          secret: "service.password"  # Reference to a secret
```

### API Key Authentication

Authenticates using an API key in a header or query parameter.

```yaml
use:
  authentications:
    - name: "apiKeyAuth"
      extension:
        apiKey:
          headerName: "X-API-Key"  # Header name
          key:
            secret: "api.key"  # Reference to a secret
```

API key in query parameter:

```yaml
extension:
  apiKey:
    parameterName: "api_key"  # Query parameter name
    key:
      secret: "api.key"
```

### OAuth 2.0 Authentication

#### Client Credentials Grant

For service-to-service authentication:

```yaml
use:
  authentications:
    - name: "oauthClientCreds"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.example.com/oauth/token"
        clientId: "service-client"
        clientSecret:
          secret: "oauth.client.secret"
        scopes:
          - "read"
          - "write"
```

#### Password Grant

For applications with direct access to user credentials:

```yaml
oauth2:
  grantType: "password"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "app-client"
  clientSecret:
    secret: "oauth.client.secret"
  username: "${ .user.username }"
  password: "${ .user.password }"
  scopes:
    - "profile"
```

#### Authorization Code Grant

For standard web application flow:

```yaml
oauth2:
  grantType: "authorization_code"
  authorizationUrl: "https://auth.example.com/oauth/authorize"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "web-client"
  clientSecret:
    secret: "oauth.client.secret"
  redirectUri: "https://your-app.example.com/callback"
  scopes:
    - "profile"
    - "email"
```

#### Client Certificate Authentication

For mTLS authentication:

```yaml
oauth2:
  grantType: "client_credentials"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "service-client"
  clientAuthentication: "tls_client_auth"
  clientCertificate:
    keystore:
      path:
        secret: "client.keystore.path"
      password:
        secret: "client.keystore.password"
      alias: "client-cert"
```

#### Additional OAuth 2.0 Options

```yaml
oauth2:
  # Basic configuration...
  
  # Token behavior
  tokenRefreshBeforeExpiry: "PT30S"  # Refresh token 30 seconds before expiry
  
  # Additional parameters
  additionalParameters:
    audience: "https://api.example.com"
    resource: "https://resource.example.com"
  
  # Token request headers
  tokenRequestHeaders:
    X-Custom-Header: "value"
  
  # Token storage
  tokenStorage: "memory"  # Options: "memory", "persistent"
  
  # Advanced settings
  validateJwt: true
  acceptedIssuers:
    - "https://auth.example.com"
  jwksUrl: "https://auth.example.com/.well-known/jwks.json"
```

### OpenID Connect Authentication

For authentication using OpenID Connect:

```yaml
use:
  authentications:
    - name: "oidcAuth"
      oidc:
        issuerUrl: "https://accounts.example.com"
        clientId: "web-client"
        clientSecret:
          secret: "oidc.client.secret"
        scopes:
          - "openid"
          - "profile"
          - "email"
```

### Digest Authentication

For HTTP Digest Authentication:

```yaml
use:
  authentications:
    - name: "digestAuth"
      digest:
        username: "username"
        password:
          secret: "digest.password"
        realm: "Protected Area"
```

### AWS Signature Authentication

For AWS API authentication:

```yaml
use:
  authentications:
    - name: "awsAuth"
      extension:
        awsSigV4:
          accessKey:
            secret: "aws.access.key"
          secretKey:
            secret: "aws.secret.key"
          region: "us-west-2"
          service: "s3"
```

### HMAC Authentication

For HMAC-based API authentication:

```yaml
use:
  authentications:
    - name: "hmacAuth"
      extension:
        hmac:
          key:
            secret: "hmac.key"
          algorithm: "HmacSHA256"
          headerName: "X-Signature"
          includedHeaders:
            - "X-Timestamp"
            - "Content-Type"
```

### JWT Authentication

For JWT-based authentication:

```yaml
use:
  authentications:
    - name: "jwtAuth"
      extension:
        jwt:
          token:
            secret: "jwt.token"
          headerName: "Authorization"
          scheme: "Bearer"
```

### Custom Authentication

For custom authentication schemes:

```yaml
use:
  authentications:
    - name: "customAuth"
      extension:
        custom:
          headers:
            X-App-ID:
              secret: "app.id"
            X-Timestamp: "${ now() }"
            X-Signature:
              expression: "${ hmacSha256(.headers['X-Timestamp'], .secrets.hmac_key) }"
```

## Authentication Reuse

Authentication configurations can be referenced across multiple workflows using a shared configuration file:

```yaml
# authentications.yaml
authentications:
  - name: "serviceAuth"
    oauth2:
      grantType: "client_credentials"
      tokenUrl: "https://auth.example.com/oauth/token"
      clientId: "service-client"
      clientSecret:
        secret: "oauth.client.secret"
```

Import in workflow:

```yaml
use:
  imports:
    - resource: "authentications.yaml"
```

## Authentication Resolution

Lemline resolves authentication in this order:

1. Check if `auth` property references a defined authentication
2. If `auth` not provided, check for inline authentication properties
3. Apply default authentication if configured

## Dynamic Authentication Selection

Authentication can be selected dynamically based on conditions:

```yaml
- selectAuth:
    switch:
      - condition: "${ .environment == 'production' }"
        do:
          - callProdApi:
              callHTTP:
                url: "https://api.example.com/data"
                auth: "prodAuth"
      
      - otherwise:
          do:
            - callDevApi:
                callHTTP:
                  url: "https://api-dev.example.com/data"
                  auth: "devAuth"
```

## Protocol-Specific Authentication

### Authentication for gRPC

```yaml
- getUserProfile:
    callGRPC:
      service: "users.UserService"
      rpc: "GetUserProfile"
      message:
        userId: "${ .userId }"
      auth: "serviceAuth"  # Reference to authentication configuration
```

### Authentication for AsyncAPI

```yaml
- publishEvent:
    callAsyncAPI:
      publish:
        channel: "orders"
        message:
          payload: "${ .order }"
        auth: "kafkaAuth"  # Reference to authentication configuration
```

### Authentication for External Resources

```yaml
- processData:
    runContainer:
      image: "data-processor:latest"
      volumes:
        - "/data:/data"
      auth: "dockerAuth"  # Reference to authentication configuration
```

## Authentication Caching

Lemline caches authentication tokens and credentials when possible:

```yaml
use:
  authentications:
    - name: "serviceAuth"
      oauth2:
        # Configuration...
      cache:
        enabled: true
        ttl: "PT1H"  # Time-to-live for cached tokens
        maxEntries: 10  # Maximum cache entries
```

## Authentication Extensions

Lemline supports authentication extensions for custom authentication providers:

```yaml
use:
  authentications:
    - name: "customProviderAuth"
      extension:
        provider: "com.example.CustomAuthProvider"
        configuration:
          key1: "value1"
          key2: "value2"
```

## Authentication with External Identity Providers

### Azure AD Authentication

```yaml
use:
  authentications:
    - name: "azureAuth"
      extension:
        azureAD:
          tenantId: "your-tenant-id"
          clientId: "your-client-id"
          clientSecret:
            secret: "azure.client.secret"
          resource: "https://management.azure.com/"
```

### Google Cloud Authentication

```yaml
use:
  authentications:
    - name: "googleAuth"
      extension:
        googleCloud:
          serviceAccount:
            secret: "google.service.account.json"
          scopes:
            - "https://www.googleapis.com/auth/cloud-platform"
```

## Security Best Practices

1. **Use secrets for credentials**: Never hardcode credentials in workflow definitions
2. **Use appropriate auth types**: Select the most appropriate auth type for each service
3. **Limit token scopes**: Request only the minimum necessary permissions
4. **Enable token validation**: Validate tokens when possible
5. **Use TLS**: Always use HTTPS for auth endpoints
6. **Rotate credentials**: Implement regular credential rotation
7. **Monitor auth failures**: Set up alerts for authentication failures
8. **Use metadata encryption**: Encrypt sensitive authentication metadata

## Related Resources

- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [API Key Authentication](lemline-howto-api-keys.md)
- [Managing Secrets](lemline-howto-secrets.md)
- [TLS Configuration](lemline-howto-tls.md)
- [Security Overview](lemline-explain-security.md)