# Secrets Reference

This reference documents all aspects of secret management in Lemline, including configuration, usage, and best practices.

## Secret Management Overview

Lemline provides a secure secrets management system that separates sensitive information from workflow definitions. This approach:

1. Prevents hardcoding sensitive values in workflows
2. Centralizes secret management
3. Enables secret rotation without workflow changes
4. Provides audit capabilities for secret access
5. Integrates with external secret management systems

## Secret Configuration Sources

Lemline can retrieve secrets from multiple sources:

### Environment Variables

Secrets stored in environment variables:

```properties
lemline.secrets.source=env
lemline.secrets.env.prefix=LEMLINE_SECRET_
```

With this configuration, environment variables with the prefix `LEMLINE_SECRET_` are treated as secrets:

```bash
export LEMLINE_SECRET_API_KEY=your-api-key
export LEMLINE_SECRET_DB_PASSWORD=your-db-password
```

These secrets are accessible as `api.key` and `db.password` in workflows.

### Properties File

Secrets stored in a properties file:

```properties
lemline.secrets.source=file
lemline.secrets.file.path=/path/to/secrets.properties
```

Example `secrets.properties` file:

```properties
api.key=your-api-key
db.password=your-db-password
oauth.client.secret=your-oauth-secret
```

### HashiCorp Vault

Secrets stored in HashiCorp Vault:

```properties
lemline.secrets.source=vault
lemline.secrets.vault.url=https://vault.example.com:8200
lemline.secrets.vault.token=${VAULT_TOKEN}
lemline.secrets.vault.path=secret/lemline
```

This configuration retrieves secrets from the `secret/lemline` path in Vault.

### AWS Secrets Manager

Secrets stored in AWS Secrets Manager:

```properties
lemline.secrets.source=aws
lemline.secrets.aws.region=us-west-2
lemline.secrets.aws.prefix=lemline/
```

This retrieves secrets with the prefix `lemline/` from AWS Secrets Manager.

### Azure Key Vault

Secrets stored in Azure Key Vault:

```properties
lemline.secrets.source=azure
lemline.secrets.azure.vault-name=lemline-vault
```

### Multiple Sources

Lemline supports multiple secret sources with fallback:

```properties
lemline.secrets.sources=env,file,vault
lemline.secrets.env.prefix=LEMLINE_SECRET_
lemline.secrets.file.path=/path/to/secrets.properties
lemline.secrets.vault.url=https://vault.example.com:8200
```

With this configuration, Lemline checks sources in order until the secret is found.

## Using Secrets in Workflows

### Secret References

Secrets are referenced in workflows using the `secret` property:

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
          secret: "oauth.client.secret"  # References a secret
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

### Script Environment Variables

Secrets can be passed to scripts:

```yaml
- runScriptTask:
    runScript:
      script:
        inline: |
          // Script code here
      environment:
        API_KEY:
          secret: "api.key"  # References a secret
```

### Container Environment Variables

Secrets can be passed to containers:

```yaml
- runContainerTask:
    runContainer:
      image: "my-image:latest"
      environment:
        DB_PASSWORD:
          secret: "database.password"  # References a secret
```

### Dynamic Secret Selection

Secrets can be selected dynamically:

```yaml
- dynamicSecretAccess:
    callHTTP:
      url: "https://api.example.com/data"
      headers:
        Authorization:
          secret: "${ .environment + '.api.key' }"  # Resolves to "dev.api.key" or "prod.api.key"
```

## Secret Types

Lemline supports various types of secrets:

### Simple String Secrets

Basic string values:

```yaml
secret: "api.key"  # References a simple string secret
```

### Structured Secrets

JSON/YAML structured secrets:

```yaml
secret: "service.credentials"  # References a structured secret
path: ".username"  # Access specific field within structured secret
```

Example structured secret:

```json
{
  "username": "service_user",
  "password": "service_password",
  "endpoint": "https://service.example.com"
}
```

### Binary Secrets

Binary data like certificates or keys:

```yaml
secret: "tls.certificate"  # References a binary secret
encoding: "base64"  # How to encode the binary data
```

### Versioned Secrets

Accessing specific versions of secrets:

```yaml
secret: "api.key#2"  # Access version 2 of the api.key secret
```

## Secret Management Features

### Secret Refresh

Lemline supports automatic secret refreshing:

```properties
lemline.secrets.refresh.enabled=true
lemline.secrets.refresh.interval=PT15M  # Refresh every 15 minutes
```

### Secret Caching

For performance, secrets can be cached:

```properties
lemline.secrets.cache.enabled=true
lemline.secrets.cache.ttl=PT1H  # 1 hour cache TTL
```

### Secret Rotation

Support for zero-downtime secret rotation:

```properties
lemline.secrets.rotation.strategy=dual-read
lemline.secrets.rotation.grace-period=PT24H  # 24 hour grace period
```

With dual-read rotation, Lemline tries the new secret first, then falls back to the old one during the grace period.

### Secret Audit

Audit logging for secret access:

```properties
lemline.secrets.audit.enabled=true
lemline.secrets.audit.log-level=INFO
lemline.secrets.audit.include-access=true
```

This logs all secret access with details about which workflow accessed which secret.

## Secret Encryption

### Secret Encryption at Rest

Lemline can encrypt secrets at rest:

```properties
lemline.secrets.encryption.enabled=true
lemline.secrets.encryption.key-provider=kms
lemline.secrets.encryption.kms.key-id=arn:aws:kms:us-west-2:123456789012:key/abcd1234
```

### Secret Encryption in Transit

Secrets are always transmitted using TLS encryption when connecting to external secret stores.

## Secret Management API

Lemline provides CLI commands for secret management:

```bash
# List available secrets
lemline secrets list

# Set a secret value
lemline secrets set api.key "your-api-key"

# Get a secret value (restricted to admins)
lemline secrets get api.key

# Delete a secret
lemline secrets delete api.key

# Rotate a secret
lemline secrets rotate db.password --strategy=dual-read
```

## Secret Management Extensions

Lemline supports custom secret management extensions:

```properties
lemline.secrets.source=custom
lemline.secrets.custom.provider-class=com.example.CustomSecretProvider
lemline.secrets.custom.config.key1=value1
```

## Integration with External Systems

### Kubernetes Integration

Integration with Kubernetes Secrets:

```properties
lemline.secrets.source=kubernetes
lemline.secrets.kubernetes.namespace=lemline
lemline.secrets.kubernetes.label-selector=app=lemline
```

### Docker Swarm Integration

Integration with Docker Swarm Secrets:

```properties
lemline.secrets.source=docker-swarm
```

### OpenShift Integration

Integration with OpenShift Secrets:

```properties
lemline.secrets.source=openshift
lemline.secrets.openshift.namespace=lemline
```

## Security Best Practices

1. **Use least privilege**: Grant minimal access to secret stores
2. **Encrypt secrets**: Always encrypt secrets at rest
3. **Rotate regularly**: Implement regular secret rotation
4. **Audit access**: Log and monitor secret access
5. **Use namespaces**: Organize secrets in logical namespaces
6. **Never log secrets**: Ensure secrets never appear in logs
7. **Use metadata**: Add metadata like expiration to secrets
8. **Separate environments**: Use different secrets for dev/test/prod

## Troubleshooting Secret Management

Common issues and solutions:

### Secret Not Found

If a secret is not found:

1. Check the secret name for typos
2. Verify the secret exists in the configured store
3. Check secret source configuration
4. Verify permissions to access the secret
5. Check environment variables if using env source

### Secret Refresh Issues

If secrets don't refresh:

1. Verify refresh is enabled
2. Check refresh interval configuration
3. Check logs for refresh errors
4. Verify connectivity to secret store

### Vault Authentication Issues

If Vault authentication fails:

1. Verify token is valid and not expired
2. Check Vault endpoint and connectivity
3. Verify policy permissions
4. Check for Vault errors in logs

## Related Resources

- [Managing Secrets in Lemline](lemline-howto-secrets.md)
- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [API Key Authentication](lemline-howto-api-keys.md)
- [TLS Configuration](lemline-howto-tls.md)
- [Security Overview](lemline-explain-security.md)