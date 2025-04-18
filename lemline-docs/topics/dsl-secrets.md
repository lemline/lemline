---
title: Secrets Management
---

# Secrets Management

## Introduction

Secure handling of sensitive information is critical in modern workflow systems. The Serverless Workflow DSL provides a structured approach to secrets management that allows workflows to securely access protected resources without exposing sensitive information in workflow definitions.

Secrets can include credentials, API keys, tokens, certificates, and other sensitive information required to access external services or systems.

## Declaring Secrets

Secrets are declared in the `use.secrets` section of a workflow definition. This establishes which secrets a workflow requires access to, without containing the actual secret values.

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: secrets-example
  version: '1.0.0'
use:
  secrets:
    - api-key-secret
    - database-credentials
    - oauth-client-secret
```

## Secret Resolution

The actual secret values are never stored within workflow definitions. Instead, the workflow runtime is responsible for retrieving secret values at execution time from a secure storage service or vault. Common secret storage solutions include:

- Kubernetes Secrets
- HashiCorp Vault
- Cloud provider secret managers (AWS Secrets Manager, Azure Key Vault, Google Secret Manager)
- Environment variables (for development environments only)

## Accessing Secrets in Workflows

Secrets can be referenced within workflow tasks using the `$SECRETS` context variable. This approach ensures that sensitive values never appear in the workflow definition itself.

### Example: Using a Secret in an HTTP Call

```yaml
do:
  - callProtectedAPI:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/data
        headers:
          Authorization: "Bearer $SECRETS.api-key-secret"
```

### Example: Authentication with Secrets

```yaml
use:
  authentications:
    customerApiAuth:
      oauth2:
        authority: https://auth.example.com
        grant: client_credentials
        client:
          id: "client-id"
          secret: "$SECRETS.oauth-client-secret"
  secrets:
    - oauth-client-secret
```

## Security Considerations and Best Practices

1. **Principle of Least Privilege**: Only request access to secrets that are absolutely necessary for the workflow's operation.

2. **Secret Rotation**: Implement regular rotation of secrets and ensure your workflow can handle updated secret values without disruption.

3. **Auditing**: Ensure your secrets management system maintains logs of which workflows accessed which secrets, for compliance and security monitoring.

4. **No Secret Values in Logs**: Configure your workflow runtime to prevent secret values from appearing in logs or error messages.

5. **Access Control**: Implement proper access controls to restrict which users or systems can deploy workflows that access sensitive secrets.

6. **Different Secrets Per Environment**: Use different secret values across development, testing, and production environments.

## Error Handling

If a workflow attempts to access a secret that doesn't exist or to which it doesn't have access, the workflow runtime will raise an error:

```
https://serverlessworkflow.io/spec/1.0.0/errors/authorization
```

This error should be handled appropriately using error handling mechanisms like the `Try` task:

```yaml
do:
  - accessProtectedResource:
      try:
        do:
          - callWithSecret:
              call: http
              with:
                method: get
                endpoint: https://api.example.com/protected
                headers:
                  Authorization: "Bearer $SECRETS.api-key-secret"
        catch:
          - error: https://serverlessworkflow.io/spec/1.0.0/errors/authorization
            as: secretError
            handle:
              - logSecretError:
                  call: function
                  with:
                    function: logError
                    arguments:
                      message: "Missing or unauthorized secret access"
```

## Integration with External Secret Management Systems

Workflow runtimes typically integrate with organization-wide secret management systems. The specific configuration of these integrations is typically handled outside the workflow definition itself, often in the runtime configuration.

The workflow simply references secrets by name, and the runtime handles resolving those references securely at execution time.

## Conclusion

Effective secrets management is a critical aspect of secure workflow design. The Serverless Workflow DSL provides a clean separation between workflow logic and sensitive information through its secrets mechanism. By following best practices for secrets management, you can build workflows that securely access protected resources while maintaining the confidentiality of sensitive information. 