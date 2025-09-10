# Managing Secrets in Lemline

This guide explains how to securely handle sensitive information like API keys, passwords, and tokens in your Lemline workflows.

## Introduction to Secrets Management

Secrets management is a critical aspect of secure workflow development. Lemline provides a structured approach to managing sensitive information without embedding it directly in your workflow definitions.

## Secrets Configuration

Lemline secrets are configured at the system level, separate from workflow definitions:

1. **System Secrets Store**: Secrets are stored in a secure storage system.
2. **Reference Mechanism**: Workflows reference secrets by name, not by value.
3. **Runtime Resolution**: Secret values are resolved at runtime, never stored in the workflow definition.

## Defining Secrets in Lemline

There are several ways to define secrets in Lemline:

### 1. Using Configuration Files

Create a `secrets.properties` file:

```properties
# API credentials
api.key=your-api-key
database.password=your-db-password
oauth.client_secret=your-oauth-secret
```

Reference this file in your Lemline configuration:

```properties
lemline.secrets.source=file
lemline.secrets.file.path=/path/to/secrets.properties
```

### 2. Using Environment Variables

Define secrets as environment variables:

```bash
export LEMLINE_SECRET_API_KEY=your-api-key
export LEMLINE_SECRET_DB_PASSWORD=your-db-password
export LEMLINE_SECRET_OAUTH_CLIENT_SECRET=your-oauth-secret
```

Configure Lemline to use environment variables:

```properties
lemline.secrets.source=env
lemline.secrets.env.prefix=LEMLINE_SECRET_
```

### 3. Using a Vault Integration

For production environments, configure Lemline to use HashiCorp Vault:

```properties
lemline.secrets.source=vault
lemline.secrets.vault.url=https://vault.example.com:8200
lemline.secrets.vault.token=your-vault-token
lemline.secrets.vault.path=secret/lemline
```

## Using Secrets in Workflows

Once secrets are configured, reference them in your workflow:

```yaml
- authenticatedCall:
    callHTTP:
      url: "https://api.example.com/data"
      headers:
        Authorization:
          secret: "api.key"  # References the secret named "api.key"
```

### Authentication Configurations

Secrets are commonly used in authentication configurations:

```yaml
use:
  authentications:
    - name: "serviceAuth"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.example.com/oauth/token"
        clientId: "service-client"
        clientSecret:
          secret: "oauth.client_secret"  # References a secret
```

### Database Credentials

When workflows interact with databases:

```yaml
- databaseOperation:
    extension:
      database:
        connection:
          url: "jdbc:postgresql://db.example.com:5432/mydb"
          username: "app_user"
          password:
            secret: "database.password"  # References a secret
```

### Dynamic Secret Resolution

In some cases, you may need to dynamically determine which secret to use:

```yaml
- dynamicSecretAccess:
    callHTTP:
      url: "https://api.example.com/data"
      headers:
        Authorization:
          secret: "${ .environment + '.api.key' }"  # Resolves to "dev.api.key" or "prod.api.key"
```

## Secret Rotation and Management

Lemline supports secret rotation strategies:

1. **Automatic Secret Refresh**: Lemline periodically refreshes secrets from the secret store.
   ```properties
   lemline.secrets.refresh.enabled=true
   lemline.secrets.refresh.interval=PT15M  # Refresh every 15 minutes
   ```

2. **Version-specific Secrets**: Access specific versions of a secret.
   ```yaml
   secret: "api.key#2"  # Access version 2 of the api.key secret
   ```

3. **Environment-specific Secrets**: Use different secrets for different environments.
   ```yaml
   secret: "${.env}.database.password"  # Resolves to dev.database.password or prod.database.password
   ```

## Security Best Practices

1. **Never hardcode secrets**: Always use the secret reference mechanism.
2. **Limit secret access**: Configure least-privilege access to the secret store.
3. **Rotate secrets regularly**: Implement a secret rotation policy.
4. **Audit secret usage**: Monitor and log secret access.
5. **Encrypt secrets at rest**: Ensure secrets are encrypted in storage.
6. **Use different secrets per environment**: Don't share secrets across environments.
7. **Avoid logging secrets**: Ensure secrets don't appear in logs.

## Troubleshooting Secret Resolution

If you encounter issues with secret resolution:

1. **Check permissions**: Ensure Lemline has access to the secret store.
2. **Verify secret names**: Confirm that the secret name in the workflow matches the name in the store.
3. **Enable debug logging**: Temporarily enable secret resolution debugging.
   ```properties
   lemline.secrets.debug=true  # WARNING: Don't enable in production!
   ```
4. **Check secret cache**: Secret values may be cached. Force a refresh if needed.
   ```bash
   lemline admin refresh-secrets
   ```

## Related Resources

- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [API Key Authentication](lemline-howto-api-keys.md)
- [TLS Configuration](lemline-howto-tls.md)
- [Security Overview](lemline-explain-security.md)