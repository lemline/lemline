# Understanding Security in Lemline

This document explains the security models, features, best practices, and configurations available in Lemline to ensure secure workflow execution.

## Security Architecture

Lemline's security architecture is built on these fundamental principles:

1. **Defense in Depth**: Multiple layers of security controls
2. **Least Privilege**: Minimal access rights for components and workflows
3. **Zero Trust**: Verify all accesses, regardless of source
4. **Secure by Default**: Conservative default security settings
5. **Security as Code**: Security controls are defined declaratively

## Authentication

### Supported Authentication Methods

Lemline supports these authentication methods for external service interactions:

| Method | Description | Use Case |
|--------|-------------|----------|
| Basic Auth | Username/password over HTTPS | Simple API authentication |
| Bearer Token | Token-based authentication | OAuth, JWT systems |
| API Key | Key in header, query or cookie | API gateway authentication |
| OAuth 2.0 | Token-based delegated authorization | Modern API platforms |
| OIDC | OpenID Connect authentication | Identity federation |
| mTLS | Mutual TLS certificate authentication | High-security environments |
| HMAC | Hash-based message authentication | API verification |
| Digest | Challenge-response authentication | Legacy systems |
| Custom | Extensible authentication | Special requirements |

### Authentication Configuration

Authentication is configured in the `use.authentications` section:

```yaml
use:
  authentications:
    - name: "serviceAuth"
      oauth2:
        grantType: "client_credentials"
        tokenUrl: "https://auth.example.com/token"
        clientId: "service-client"
        clientSecret:
          secret: "oauth.client.secret"
        scopes:
          - "read"
          - "write"
```

### Authentication Selection

Reference authentication configurations in tasks:

```yaml
- fetchData:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      auth: "serviceAuth"  # Reference to authentication configuration
```

## Secret Management

### Secret Sources

Secrets can be stored and retrieved from:

| Source | Description | Pros | Cons |
|--------|-------------|------|------|
| Environment Variables | OS environment variables | Simple, universal | Limited features, not centralized |
| Properties Files | Encrypted properties files | Simple, portable | Manual management, limited features |
| HashiCorp Vault | Dedicated secret management | Comprehensive, enterprise-ready | Requires infrastructure |
| AWS Secrets Manager | AWS-native secret management | Cloud-native, managed | AWS-specific |
| Azure Key Vault | Azure-native secret management | Cloud-native, managed | Azure-specific |
| Kubernetes Secrets | Kubernetes-native secrets | Container-native | Kubernetes-specific |

### Secret References

Reference secrets in workflows without embedding them:

```yaml
- authenticatedCall:
    callHTTP:
      url: "https://api.example.com/data"
      headers:
        X-API-Key:
          secret: "api.key"  # References the secret named "api.key"
```

### Secret Rotation

Lemline supports secret rotation strategies:

1. **Scheduled Rotation**: Periodically refresh secrets
2. **On-Demand Rotation**: Manually trigger rotation
3. **Version-Specific Secrets**: Refer to specific secret versions
4. **Dual-Read Rotation**: Read from both old and new secrets during transition

Configure secret refresh:

```properties
# Enable periodic secret refresh
lemline.secrets.refresh.enabled=true
lemline.secrets.refresh.interval=PT15M  # Refresh every 15 minutes
```

### Secret Scope

Secrets can have different scopes:

1. **Global Secrets**: Available to all workflows
2. **Namespace Secrets**: Available to workflows in a specific namespace
3. **Workflow Secrets**: Available only to specific workflows

## Transport Security

### TLS Configuration

Configure TLS for secure communications:

```yaml
use:
  connections:
    - name: "secureService"
      type: "http"
      endpoint: "https://secure.example.com"
      tls:
        enabled: true
        protocols:
          - "TLSv1.2"
          - "TLSv1.3"
        cipherSuites:
          - "TLS_AES_128_GCM_SHA256"
          - "TLS_AES_256_GCM_SHA384"
        verifyHostname: true
        truststore:
          path:
            secret: "tls.truststore.path"
          password:
            secret: "tls.truststore.password"
```

### Certificate Verification

Control certificate verification behavior:

```yaml
tls:
  verifyHostname: true  # Verify hostname in certificate
  verifyCertificate: true  # Verify certificate validity
  allowSelfSigned: false  # Reject self-signed certificates
```

### Client Certificates

Configure mutual TLS (mTLS) with client certificates:

```yaml
tls:
  keystore:
    path:
      secret: "client.keystore.path"
    password:
      secret: "client.keystore.password"
    alias: "client-cert"
```

## Data Security

### Input Validation

Validate input data with schemas:

```yaml
input:
  schema:
    type: "object"
    required: ["orderId"]
    properties:
      orderId:
        type: "string"
        pattern: "^ORD-[0-9]{6}$"
      amount:
        type: "number"
        minimum: 0
        maximum: 10000
```

### Output Validation

Validate output data:

```yaml
output:
  schema:
    type: "object"
    properties:
      status:
        type: "string"
        enum: ["success", "failure"]
      result:
        type: "object"
```

### Data Encryption

Configure data encryption:

```yaml
# Data-at-rest encryption
lemline.security.encryption.enabled=true
lemline.security.encryption.algorithm=AES/GCM/NoPadding
lemline.security.encryption.key-provider=vault
lemline.security.encryption.vault.path=lemline/keys/data-encryption

# Sensitive data detection
lemline.security.sensitive-data.detection.enabled=true
lemline.security.sensitive-data.detection.patterns=CREDIT_CARD,SSN,PASSWORD
```

### Sensitive Data Handling

Handle sensitive data securely:

```yaml
# Mask sensitive data in logs
lemline.security.logging.mask-sensitive=true
lemline.security.logging.sensitive-patterns=CREDIT_CARD,SSN,PASSWORD

# Automatic redaction in responses
lemline.security.redaction.enabled=true
lemline.security.redaction.fields=password,creditCard,ssn
```

## Access Control

### Role-Based Access Control (RBAC)

Configure role-based permissions:

```properties
# Enable RBAC
lemline.security.rbac.enabled=true
lemline.security.rbac.provider=ldap

# Role definitions
lemline.security.rbac.roles.admin=workflow:*
lemline.security.rbac.roles.operator=workflow:start,workflow:stop,workflow:read
lemline.security.rbac.roles.viewer=workflow:read
```

### Resource Access Control

Control access to workflow resources:

```yaml
# Workflow metadata
metadata:
  security:
    requiredRoles:
      - "order-processor"
    requiredPermissions:
      - "orders:process"
```

### API Security

Secure the Lemline API:

```properties
# API authentication
lemline.api.auth.enabled=true
lemline.api.auth.type=oauth2

# OAuth2 configuration
lemline.api.auth.oauth2.issuer=https://auth.example.com
lemline.api.auth.oauth2.audience=lemline-api
```

## Integration Security

### Service Mesh Integration

Configure service mesh security:

```properties
# Istio integration
lemline.security.service-mesh.enabled=true
lemline.security.service-mesh.type=istio
lemline.security.service-mesh.istio.auth-policy=MUTUAL_TLS
```

### API Gateway Integration

Configure API gateway integration:

```properties
# API gateway integration
lemline.security.api-gateway.enabled=true
lemline.security.api-gateway.type=kong
lemline.security.api-gateway.kong.admin-url=http://kong:8001
```

## Runtime Security

### Secure Execution Environment

Configure execution environment security:

```properties
# Sandbox execution
lemline.security.sandbox.enabled=true
lemline.security.sandbox.type=jvm
lemline.security.sandbox.resources.max-memory=512M
lemline.security.sandbox.timeout=PT5M
```

### Resource Limits

Apply resource limits to workflows:

```yaml
# Workflow resource limits
metadata:
  resources:
    memory: 256M
    cpu: 0.5
    timeout: PT10M
    maxRetries: 3
```

### Container Security

Configure container security for `runContainer` tasks:

```yaml
- processData:
    runContainer:
      image: "data-processor:latest"
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        readOnlyRootFilesystem: true
        allowPrivilegeEscalation: false
        capabilities:
          drop:
            - "ALL"
      resources:
        limits:
          memory: 256M
          cpu: 0.5
```

## Auditing and Monitoring

### Security Auditing

Configure security audit logging:

```properties
# Audit logging
lemline.security.audit.enabled=true
lemline.security.audit.destination=file
lemline.security.audit.file.path=/var/log/lemline/audit.log
lemline.security.audit.events=AUTH_FAILURE,SECRET_ACCESS,ADMIN_ACTION
```

### Security Monitoring

Configure security monitoring:

```properties
# Security monitoring
lemline.security.monitoring.enabled=true
lemline.security.monitoring.alerts.enabled=true
lemline.security.monitoring.alerts.destination=slack
lemline.security.monitoring.alerts.slack.webhook=https://hooks.slack.com/services/xxx/yyy/zzz
```

## Common Security Patterns

### Token Exchange

Exchange tokens between authentication systems:

```yaml
- exchangeToken:
    callHTTP:
      url: "https://auth.example.com/token"
      method: "POST"
      headers:
        Content-Type: "application/x-www-form-urlencoded"
      body:
        format: "form"
        data:
          grant_type: "urn:ietf:params:oauth:grant-type:token-exchange"
          subject_token: "${ .sourceToken }"
          subject_token_type: "urn:ietf:params:oauth:token-type:access_token"
          audience: "https://target-service.example.com"
      auth: "tokenClientAuth"
```

### Secure Session Handling

Manage secure sessions:

```yaml
- createSession:
    callHTTP:
      url: "https://auth.example.com/sessions"
      method: "POST"
      body:
        username: "${ .username }"
        password:
          secret: "user.password"
      output:
        from: ".sessionToken"
        as: "sessionToken"

- useSession:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      headers:
        X-Session-Token: "${ .sessionToken }"
```

### JWE Encryption

Use JWE (JSON Web Encryption) for secure data exchange:

```yaml
- encryptData:
    extension:
      jwe:
        payload: "${ .sensitiveData }"
        recipient:
          key:
            secret: "recipient.public.key"
        encryptionMethod: "A256GCM"
```

## Security Best Practices

### Authentication Best Practices

1. **Use Modern Auth Protocols**: Prefer OAuth 2.0, OIDC over basic auth
2. **Rotate Credentials**: Regularly rotate credentials and tokens
3. **Limit Scopes**: Request only the permissions you need
4. **Use Short-Lived Tokens**: Prefer short-lived tokens over long-lived ones
5. **Secure Token Storage**: Securely store and transmit tokens

### Secret Management Best Practices

1. **No Hardcoded Secrets**: Never hardcode secrets in workflows
2. **Use Secret References**: Always use secret references
3. **Centralized Secret Store**: Use a dedicated secret management system
4. **Secret Rotation**: Implement a secret rotation strategy
5. **Audit Secret Access**: Monitor and log secret access

### Network Security Best Practices

1. **Use TLS 1.2+**: Disable older TLS versions
2. **Strong Cipher Suites**: Use strong cipher suites
3. **Certificate Validation**: Always validate certificates
4. **Secure API Gateways**: Route traffic through API gateways
5. **Network Segmentation**: Limit network access between components

### Data Security Best Practices

1. **Schema Validation**: Validate all inputs and outputs
2. **Data Minimization**: Only collect and process necessary data
3. **Encrypt Sensitive Data**: Encrypt sensitive data in transit and at rest
4. **Masking and Tokenization**: Mask or tokenize sensitive data
5. **Data Classification**: Classify data by sensitivity

### Development Security Best Practices

1. **Security Code Reviews**: Review code for security issues
2. **Dependency Scanning**: Scan dependencies for vulnerabilities
3. **Security Testing**: Implement security testing in CI/CD
4. **Secure Configuration**: Use secure default configurations
5. **Security Documentation**: Document security features and controls

## Security Troubleshooting

### Common Security Issues

1. **Certificate Issues**: Expired or invalid certificates
2. **Authentication Failures**: Incorrect credentials or scopes
3. **Authorization Errors**: Insufficient permissions
4. **Input Validation Errors**: Invalid or malicious input
5. **Rate Limiting**: Exceeded API rate limits

### Diagnosing Security Issues

1. **Check Logs**: Review security logs for errors
2. **Verify Configurations**: Check security configurations
3. **Test Connections**: Test connections independently
4. **Review Permissions**: Verify sufficient permissions
5. **Check Certificates**: Verify certificate validity and trust

### Security Debug Mode

Enable security debug mode for troubleshooting:

```properties
# Security debug logging
lemline.security.debug=true
lemline.security.debug.tls=true
lemline.security.debug.auth=true
lemline.security.debug.secrets=true
```

**Warning**: Only enable security debug mode temporarily in non-production environments, as it may log sensitive information.

## Related Resources

- [Authentication Reference](lemline-ref-auth.md)
- [Secrets Management](lemline-howto-secrets.md)
- [TLS Configuration](lemline-howto-tls.md)
- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [API Key Authentication](lemline-howto-api-keys.md)