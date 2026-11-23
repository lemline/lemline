---
title: Secrets Management
---

# Secrets Management

## Introduction

Secure handling of sensitive information is critical in modern workflow systems. The Open Workflow DSL provides a structured approach to secrets management that allows workflows to securely access protected resources without exposing sensitive information in workflow definitions.

Secrets can include credentials, API keys, tokens, certificates, and other sensitive information required to access external services or systems.

## Declaring Secrets

Secrets are declared in the `use.secrets` section of a workflow definition. This establishes which secrets a workflow requires access to, without containing the actual secret values.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: secrets-example
  version: '1.0.0'
use:
  secrets:
    - api-key-secret
    - database-credentials
    - oauth-client-secret
do:
  - inspectInput:
      set: ${ . }
```

## Secret Resolution

The actual secret values are never stored within workflow definitions. The workflow runtime retrieves them at execution time.

**In Lemline**, each declared name is read from the runner environment variable with exactly the same name (for example `use.secrets: [PAYMENT_API_KEY]` reads `PAYMENT_API_KEY`). Inject these variables with your platform: Kubernetes Secrets, Docker secrets, or your deployment tool. See [Managing Secrets in Lemline](lemline-howto-secrets.md).

## Accessing Secrets in Workflows

Secrets are available in runtime expressions through the `$secrets` argument. A workflow only sees the secrets it declares.

### Example: Using a Secret in an HTTP Call

```yaml
use:
  secrets:
    - API_KEY
do:
  - callProtectedAPI:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/data
        headers:
          Authorization: '${ "Bearer " + $secrets.API_KEY }'
```

### Example: Structured Secret

When a secret value is a JSON object, its fields can be read directly:

```yaml
use:
  secrets:
    - DB_CREDENTIALS   # e.g. {"username": "app", "password": "s3cr3t"}
do:
  - openSession:
      call: http
      with:
        method: post
        endpoint: https://db-proxy.example.com/sessions
        body:
          user: ${ $secrets.DB_CREDENTIALS.username }
          password: ${ $secrets.DB_CREDENTIALS.password }
```

### Example: Secret in an Authentication Policy

```yaml
use:
  secrets:
    - API_TOKEN
do:
  - callProtectedAPI:
      call: http
      with:
        method: get
        endpoint:
          uri: https://api.example.com/data
          authentication:
            bearer:
              token: ${ $secrets.API_TOKEN }
```

## Security Considerations and Best Practices

1. **Principle of Least Privilege**: Only request access to secrets that are absolutely necessary for the workflow's operation.

2. **Secret Rotation**: Implement regular rotation of secrets and ensure your workflow can handle updated secret values without disruption.

3. **Auditing**: Ensure your secrets management system maintains logs of which workflows accessed which secrets, for compliance and security monitoring.

4. **No Secret Values in Logs**: Configure your workflow runtime to prevent secret values from appearing in logs or error messages.

5. **Access Control**: Implement proper access controls to restrict which users or systems can deploy workflows that access sensitive secrets.

6. **Different Secrets Per Environment**: Use different secret values across development, testing, and production environments.

## Error Handling

If a declared secret is not available, the workflow faults with an authorization error:

```
https://open-workflow-specification.org/spec/1.0.0/errors/authorization
```

In Lemline, this check runs before each step, outside of any task: the workflow faults directly with the title `Required secret '<name>' is unavailable` (status 403), and a `try` task cannot catch it. Make the secret available on the runners, then start a new instance.

## Integration with External Secret Management Systems

The workflow simply references secrets by name; the runtime resolves them at execution time. Lemline reads them from environment variables and has no built-in connector to a secret manager: use your platform to expose secrets from Vault, a cloud secret manager or Kubernetes as environment variables of the runners.

## Conclusion

Effective secrets management is a critical aspect of secure workflow design. The Open Workflow DSL provides a clean separation between workflow logic and sensitive information through its secrets mechanism. By following best practices for secrets management, you can build workflows that securely access protected resources while maintaining the confidentiality of sensitive information.
