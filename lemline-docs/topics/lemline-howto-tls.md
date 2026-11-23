# Configuring TLS for Secure Communications

This guide lists the TLS settings Lemline actually provides and where TLS is handled outside Lemline.

## Where TLS Applies

| Connection | How TLS is configured |
|------------|-----------------------|
| Clients and dashboard → gRPC gateway | `lemline.gateway.tls.*` (see below) |
| Runner → Kafka | `lemline.messaging.kafka.security-protocol` and SASL settings |
| Runner → RabbitMQ | `lemline.messaging.rabbitmq.ssl-enabled` |
| Workflow `call: http` → external service | Use an `https://` endpoint; certificates are checked against the default trust store of the Java runtime |

Lemline has no global TLS settings (protocol versions, cipher suites, keystores, certificate expiry monitoring) and no per-call TLS options in workflow definitions.

## Gateway TLS

The gRPC gateway serves TLS by default.

<!-- workflow-example: data -->
```yaml
lemline:
  gateway:
    tls:
      enabled: true
      certificate: /etc/lemline/tls/server.crt
      private-key: /etc/lemline/tls/server.key
```

- `certificate` and `private-key` are required when TLS is enabled.
- JWT authentication (`lemline.gateway.authentication.enabled`, on by default) requires TLS.
- Set `enabled: false` only on a trusted local network, together with `lemline.gateway.authentication.enabled: false`.

### Client Certificates (mTLS)

<!-- workflow-example: data -->
```yaml
lemline:
  gateway:
    tls:
      enabled: true
      certificate: /etc/lemline/tls/server.crt
      private-key: /etc/lemline/tls/server.key
      client-auth: required        # none, request or required
      trust-store: /etc/lemline/tls/clients.p12
      trust-store-password: change-me
```

`trust-store` is required when `client-auth` is `request` or `required`. Pass the password as an environment variable (`LEMLINE_GATEWAY_TLS_TRUST_STORE_PASSWORD`) rather than in the file.

## Kafka TLS and SASL

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: kafka
    kafka:
      brokers: kafka.example.com:9093
      security-protocol: SASL_SSL      # Kafka security.protocol
      sasl-mechanism: PLAIN
      sasl-username: lemline
      sasl-password: change-me
```

`security-protocol` is passed to Kafka as `security.protocol`. When `sasl-username` and `sasl-password` are both set, Lemline configures the PLAIN login module.

## RabbitMQ TLS

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: rabbitmq
    rabbitmq:
      hostname: rabbitmq.example.com
      port: 5671
      ssl-enabled: true
```

## Calling HTTPS Services from Workflows

Use an `https://` URI in the endpoint:

<code-block lang="yaml" src="workflows/lemline-howto-tls/calling-https-services-from-workflows.yaml" include-lines="6-11"/>

The server certificate must be trusted by the Java runtime that runs Lemline. A call to a server with an untrusted or invalid certificate fails.

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Gateway does not start | `certificate` and `private-key` paths exist and are readable; `trust-store` is set when `client-auth` is not `none` |
| Gateway clients get `UNAUTHENTICATED` | Authentication is enabled: send a valid bearer token |
| Runner cannot connect to Kafka | `security-protocol`, SASL credentials and the TLS port of the brokers |
| `call: http` fails on HTTPS | The service certificate chain is trusted by the Java runtime |

## Related Resources

- [gRPC Gateway Reference](lemline-ref-gateway.md)
- [Runner Configuration Reference](lemline-ref-config.md)
- [Message Brokers](lemline-howto-brokers.md)
