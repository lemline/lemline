# Configuring TLS for Secure Communications

This guide explains how to configure Transport Layer Security (TLS) for secure communications in Lemline workflows.

## Introduction to TLS in Lemline

TLS (Transport Layer Security) ensures secure communications between your workflows and external services by:

1. Encrypting data in transit
2. Verifying the identity of remote servers
3. Protecting against man-in-the-middle attacks

Lemline supports TLS configuration at multiple levels:

1. System-wide TLS defaults
2. Service-specific TLS settings
3. Individual request TLS options

## System-Wide TLS Configuration

Configure default TLS behavior in your Lemline configuration:

```properties
# Enable/disable TLS certificate validation (default: true)
lemline.tls.verification.enabled=true

# Default minimum TLS version (default: TLSv1.2)
lemline.tls.minimum-version=TLSv1.3

# System truststore configuration
lemline.tls.truststore.path=/path/to/truststore.jks
lemline.tls.truststore.password=${TRUSTSTORE_PASSWORD}
lemline.tls.truststore.type=JKS

# System keystore for client certificates
lemline.tls.keystore.path=/path/to/keystore.p12
lemline.tls.keystore.password=${KEYSTORE_PASSWORD}
lemline.tls.keystore.type=PKCS12
```

## Using Client Certificates

For services requiring client certificates (mutual TLS):

```yaml
use:
  authentications:
    - name: "mtlsAuth"
      extension:
        mtls:
          keystore:
            path:
              secret: "client.keystore.path"
            password:
              secret: "client.keystore.password"
            alias: "client-cert"
```

Using this authentication in a request:

```yaml
- secureApiCall:
    callHTTP:
      url: "https://secure-api.example.com/data"
      method: "GET"
      auth: "mtlsAuth"
```

## Service-Specific TLS Configuration

Define service-specific TLS settings:

```yaml
use:
  connections:
    - name: "legacyService"
      endpoint: "https://legacy-api.example.com"
      tls:
        verifyHostname: true
        minimumVersion: "TLSv1.2"
        truststore:
          path:
            secret: "legacy.truststore.path"
          password:
            secret: "legacy.truststore.password"
```

Using the connection in a request:

```yaml
- callLegacyService:
    callHTTP:
      connection: "legacyService"
      path: "/api/data"
      method: "GET"
```

## Request-Level TLS Options

Configure TLS for individual requests:

```yaml
- oneTimeSecureCall:
    callHTTP:
      url: "https://api.example.com/data"
      method: "GET"
      tls:
        verifyHostname: true
        minimumVersion: "TLSv1.3"
        cipherSuites:
          - "TLS_AES_256_GCM_SHA384"
          - "TLS_CHACHA20_POLY1305_SHA256"
```

## Managing Custom Certificate Authorities

If your services use custom or internal CAs:

### Adding CA Certificates to Truststore

1. Create a custom truststore:
   ```bash
   keytool -import -file custom-ca.crt -alias custom-ca -keystore custom-truststore.jks
   ```

2. Configure Lemline to use this truststore:
   ```properties
   lemline.tls.truststore.path=/path/to/custom-truststore.jks
   lemline.tls.truststore.password=${TRUSTSTORE_PASSWORD}
   ```

### Per-Service CA Certificates

For services with specific CA requirements:

```yaml
use:
  connections:
    - name: "internalService"
      endpoint: "https://internal-api.company.com"
      tls:
        certificates:
          - path: "/path/to/internal-ca.pem"
```

## Handling Self-Signed Certificates

For development or internal systems with self-signed certificates:

```yaml
use:
  connections:
    - name: "devService"
      endpoint: "https://dev-api.example.com"
      tls:
        verifyHostname: false  # Warning: Only for development!
        allowSelfSigned: true  # Warning: Only for development!
```

**Warning**: Disabling certificate verification reduces security. Use only in controlled environments.

## TLS Protocol Versions and Cipher Suites

Specify acceptable protocol versions and cipher suites:

```properties
# Supported protocol versions (comma-separated)
lemline.tls.protocols=TLSv1.3,TLSv1.2

# Supported cipher suites (comma-separated)
lemline.tls.cipher-suites=TLS_AES_256_GCM_SHA384,TLS_CHACHA20_POLY1305_SHA256,TLS_AES_128_GCM_SHA256
```

## Certificate Pinning

For high-security scenarios, implement certificate pinning:

```yaml
use:
  connections:
    - name: "criticalService"
      endpoint: "https://critical-api.example.com"
      tls:
        pinning:
          - hash: "sha256/BASE64HASH="  # Public key hash
          - hash: "sha256/BACKUPHASH="  # Backup hash
```

This ensures the service presents a certificate with a matching public key hash.

## Certificate Expiry Monitoring

Lemline can monitor certificate expiry dates:

```properties
# Enable certificate expiry monitoring
lemline.tls.monitor-expiry=true

# Warning threshold before expiry (in days)
lemline.tls.expiry-warning-days=30
```

When enabled, logs and metrics will alert you to certificates nearing expiration.

## Troubleshooting TLS Issues

Common TLS issues and solutions:

### Certificate Validation Failures

If you encounter certificate validation errors:

1. Verify the server's certificate is valid and not expired
2. Ensure the server's certificate is issued by a trusted CA
3. Check that the hostname in the URL matches the certificate's CN/SAN
4. Ensure your truststore contains the necessary CA certificates

### Handshake Failures

For TLS handshake failures:

1. Check that the client and server support a common TLS version
2. Verify they have common cipher suites
3. Ensure your TLS configuration isn't too restrictive

### Client Certificate Issues

For mutual TLS problems:

1. Verify the client certificate is valid and not expired
2. Ensure the certificate has the correct usage extensions
3. Check that the private key is accessible and the password is correct

## Security Best Practices

1. **Use TLS 1.3**: Prefer TLS 1.3 when possible for better security
2. **Disable old protocols**: Avoid TLS 1.0 and 1.1
3. **Use strong cipher suites**: Prefer GCM and CHACHA20 ciphers
4. **Validate certificates**: Always verify certificates in production
5. **Use certificate pinning**: For critical services, implement certificate pinning
6. **Rotate certificates**: Implement regular certificate rotation
7. **Monitor expiration**: Proactively monitor certificate expiration dates

## Related Resources

- [OAuth 2.0 Authentication](lemline-howto-oauth2.md)
- [API Key Authentication](lemline-howto-api-keys.md)
- [Managing Secrets](lemline-howto-secrets.md)
- [Security Overview](lemline-explain-security.md)