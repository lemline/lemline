# Using API Keys for Authentication

This guide explains how to configure and use API keys for authenticating HTTP requests in Lemline workflows.

## Introduction to API Key Authentication

API keys are simple yet effective authentication tokens that are passed with API requests to identify and authorize the client. Unlike OAuth tokens, API keys are static and don't expire (unless manually rotated).

Lemline supports several methods for using API keys:

1. Header-based authentication
2. Query parameter authentication
3. Bearer token authentication

## Configuring API Key Authentication

### Header-Based API Keys

The most common way to use API keys is through HTTP headers:

```yaml
use:
  authentications:
    - name: "apiKeyAuth"
      extension:
        apiKey:
          headerName: "X-API-Key"
          key: "your-api-key-here"  # Not recommended - use secrets instead
```

A more secure approach is to store the API key as a secret:

```yaml
use:
  authentications:
    - name: "apiKeyAuth"
      extension:
        apiKey:
          headerName: "X-API-Key"
          key:
            secret: "api.key"  # Reference to a secret
```

### Query Parameter API Keys

Some APIs require keys to be passed as query parameters:

```yaml
use:
  authentications:
    - name: "queryApiKeyAuth"
      extension:
        apiKey:
          parameterName: "api_key"
          key:
            secret: "api.key"
```

### Bearer Token Authentication

For APIs that use Bearer token authentication:

```yaml
use:
  authentications:
    - name: "bearerAuth"
      bearer:
        token:
          secret: "api.bearer.token"
```

### Custom Authentication Headers

For APIs with custom authentication schemes:

```yaml
use:
  authentications:
    - name: "customAuth"
      extension:
        apiKey:
          headerName: "Authorization"
          prefix: "ApiKey "  # Results in "Authorization: ApiKey your-key"
          key:
            secret: "api.key"
```

## Using API Key Authentication

Once configured, you can reference the authentication in your API calls:

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      auth: "apiKeyAuth"  # Reference to the authentication configuration
```

The authentication details are automatically applied to the request.

## Dynamic API Key Selection

In some scenarios, you might need to select different API keys dynamically:

```yaml
- selectApiKey:
    switch:
      - condition: "${ .environment == 'production' }"
        do:
          - fetchDataProd:
              callHTTP:
                url: "https://api.example.com/data"
                auth: "prodApiKeyAuth"
      
      - otherwise:
          do:
            - fetchDataDev:
                callHTTP:
                  url: "https://api-dev.example.com/data"
                  auth: "devApiKeyAuth"
```

## Using Multiple Authentication Methods

For APIs requiring multiple authentication methods:

```yaml
use:
  authentications:
    - name: "complexAuth"
      extension:
        apiKey:
          headerName: "X-API-Key"
          key:
            secret: "api.key"
          additionalHeaders:
            X-App-ID:
              secret: "app.id"
            X-Timestamp: "${ now() }"
```

## Inline API Key Configuration

For one-off use cases, you can define authentication inline:

```yaml
- oneTimeApiCall:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      headers:
        X-API-Key:
          secret: "api.key"
```

However, for reusable authentication configurations, defining them in `use.authentications` is recommended.

## API Key Security Best Practices

1. **Use secrets**: Never hardcode API keys in workflow definitions
2. **Limit permissions**: Use API keys with the minimum required permissions
3. **Use different keys**: Use different API keys for different environments
4. **Rotate regularly**: Implement a rotation schedule for API keys
5. **Monitor usage**: Track API key usage for suspicious patterns
6. **Use HTTPS**: Always use HTTPS for requests containing API keys
7. **Prefer headers**: Use header-based authentication rather than query parameters when possible

## Troubleshooting API Key Authentication

Common issues and solutions:

### API Key Not Being Sent

If your API key isn't being sent with requests:

1. Check the authentication configuration name and reference
2. Verify the header or parameter name matches the API requirements
3. Ensure the secret is correctly defined and accessible

### Incorrect API Key Format

If the API key is being sent but rejected:

1. Check if the API requires a prefix (like "Bearer ")
2. Verify key format requirements (some APIs have specific formats)
3. Ensure the key is not being truncated or modified

### Authentication Failures

If you're receiving authentication errors:

1. Check that the API key is valid and active
2. Verify you're using the correct key for the environment
3. Check if the API has rate limiting or IP restrictions

## Related Resources

- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [Managing Secrets](lemline-howto-secrets.md)
- [TLS Configuration](lemline-howto-tls.md)
- [Authentication Reference](lemline-ref-auth.md)