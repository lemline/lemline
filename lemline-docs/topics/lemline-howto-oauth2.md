# Configuring OAuth 2.0 Authentication

This guide explains how to configure OAuth 2.0 authentication for HTTP calls in your Lemline workflows.

## OAuth 2.0 Overview

OAuth 2.0 is an authorization framework that enables a third-party application to obtain limited access to a service, either on behalf of a resource owner or by allowing the third-party application to obtain access on its own behalf.

Lemline supports OAuth 2.0 for securing API calls made with:
- `callHTTP` tasks
- `callOpenAPI` tasks
- `callGRPC` tasks

## Basic OAuth 2.0 Configuration

To configure an OAuth 2.0 authentication policy, include it in your workflow's `use.authentications` section:

```yaml
use:
  authentications:
    - name: "serviceAuth"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.example.com/oauth/token"
        clientId: "your-client-id"
        clientSecret: "your-client-secret"
        scopes:
          - "read"
          - "write"
```

This creates a reusable authentication policy that can be referenced in tasks:

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      auth: "serviceAuth"
```

## OAuth 2.0 Grant Types

Lemline supports multiple OAuth 2.0 grant types:

### Client Credentials Grant

Used for server-to-server authentication:

```yaml
oauth2:
  grantType: "client_credentials"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "your-client-id"
  clientSecret: "your-client-secret"
  scopes:
    - "api:read"
```

### Resource Owner Password Credentials

Used when you have direct access to user credentials:

```yaml
oauth2:
  grantType: "password"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "your-client-id"
  clientSecret: "your-client-secret"
  username: "${ .user.username }"
  password: "${ .user.password }"
  scopes:
    - "profile"
```

### Authorization Code Grant

```yaml
oauth2:
  grantType: "authorization_code"
  authorizationUrl: "https://auth.example.com/oauth/authorize"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "your-client-id"
  clientSecret: "your-client-secret"
  redirectUri: "https://your-app.example.com/callback"
  scopes:
    - "profile"
    - "email"
```

Note: This grant type typically requires user interaction and may not be suitable for all workflow scenarios.

## Using Secrets for Credentials

For security, store sensitive values as secrets rather than embedding them directly:

```yaml
use:
  authentications:
    - name: "secureServiceAuth"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.example.com/oauth/token"
        clientId:
          secret: "service_client_id"
        clientSecret:
          secret: "service_client_secret"
        scopes:
          - "api:read"
```

See [Managing Secrets](lemline-howto-secrets.md) for more information on secret configuration.

## Token Management

OAuth 2.0 tokens are managed automatically by Lemline:

1. Tokens are obtained when first needed
2. Tokens are cached for reuse within their validity period
3. Tokens are automatically refreshed when they expire
4. Unique token caches are maintained for different authentication configurations

Configure token behavior with additional options:

```yaml
oauth2:
  grantType: "client_credentials"
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: "your-client-id"
  clientSecret: "your-client-secret"
  tokenRefreshBeforeExpiry: "PT30S"  # Refresh token 30 seconds before expiry
  additionalParameters:
    audience: "https://api.example.com"
```

## Error Handling

When OAuth authentication fails, Lemline raises an `authentication` error:

```yaml
- secureApiCall:
    try:
      do:
        - callApi:
            callHTTP:
              url: "https://api.example.com/protected"
              auth: "serviceAuth"
      catch:
        - error:
            with:
              type: "https://serverlessworkflow.io/spec/1.0.0/errors/authentication"
          do:
            - handleAuthError:
                # Handle authentication failure
```

## Request Flow

Understanding the OAuth 2.0 request flow in Lemline:

1. When a task requires authentication, Lemline checks for a valid token
2. If no valid token exists, a token request is made to the token endpoint
3. The received token is cached with its expiration time
4. The token is added to the API request as a Bearer token
5. When the token expires, a new one is automatically requested

## Advanced Configuration

### Token Introspection

Enable token introspection for additional token validation:

```yaml
oauth2:
  # Basic configuration...
  tokenIntrospectionUrl: "https://auth.example.com/oauth/introspect"
  tokenIntrospectionMethod: "POST"
```

### Custom Headers

Add custom headers to token requests:

```yaml
oauth2:
  # Basic configuration...
  tokenRequestHeaders:
    X-Custom-Header: "value"
```

### Using Expressions

You can use expressions for dynamic configuration:

```yaml
oauth2:
  grantType: "client_credentials"
  tokenUrl: "${ .config.oauth.tokenUrl }"
  clientId: "${ .config.oauth.clientId }"
  clientSecret: "${ .config.oauth.clientSecret }"
```

## Best Practices

1. **Use appropriate grant types**: Choose the correct grant type for your scenario
2. **Store credentials as secrets**: Never hardcode OAuth credentials
3. **Request minimal scopes**: Only request the scopes you actually need
4. **Handle authentication errors**: Add proper error handling for auth failures
5. **Configure token expiry buffer**: Set appropriate token refresh timing
6. **Understand token lifetime**: Be aware of how token caching works
7. **Use secure connections**: Always use HTTPS for token and API endpoints

## Related Resources

- [API Key Authentication](lemline-howto-api-keys.md)
- [Managing Secrets](lemline-howto-secrets.md)
- [TLS Configuration](lemline-howto-tls.md)
- [Authentication Reference](lemline-ref-auth.md)