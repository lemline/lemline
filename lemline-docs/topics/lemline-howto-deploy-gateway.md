# Deploy the gRPC gateway

Run the gateway as a separate Lemline process to let applications start workflows and read their history. The process uses the same Lemline binary as the workers. It takes its own listen ports and can run on a separate host when the configured backends are reachable from both.

## Connect the gateway to the workers' infrastructure

1. Point `lemline.database` at the orchestration database used by the workers. The gateway reads definitions and stores accepted starts there.
2. Point `lemline.messaging` at the workers' broker and use the same commands channel. Keep a worker running with `lemline listen` in each namespace you intend to serve.
3. Point `lemline.analytics` at the lifecycle analytics store populated by the workers. See [Lifecycle Analytics Reference](lemline-ref-analytics.md) for the datasource and ingestion settings. For a same-host SQLite installation, use the same local database files in both processes.
4. If this gateway should serve a subset of namespaces, set `lemline.namespaces` in its configuration. When unset, the gateway serves all namespaces allowed by the caller's token.

## Enable TLS and bearer authentication

Use a server certificate for the gateway hostname and a JWT issuer that publishes verification keys at a JWKS URL. Merge the following shape into the gateway's existing configuration. Replace the paths and issuer URLs with the values for your environment. See [Gateway TLS](lemline-howto-tls.md#gateway-tls) for certificate settings, including client certificates.

<!-- workflow-example: data -->
```yaml
lemline:
  gateway:
    tls:
      enabled: true
      certificate: /etc/lemline/tls/gateway.pem
      private-key: /etc/lemline/tls/gateway-key.pem
    authentication:
      enabled: true
      jwt:
        issuer: https://identity.example.com/
        jwks-url: https://identity.example.com/.well-known/jwks.json
```

Start the gateway with that file. Configure a different metrics port if the worker uses the default port on the same host.

```bash
lemline --config /etc/lemline/gateway.yaml gateway start --grpc-port 9000 --metrics-port 8082
```

## Verify an authenticated request

Install `grpcurl` and `jq`, and set these variables in the client shell before making the call:

| Variable | Value |
|---|---|
| `LEMLINE_SOURCE` | Absolute path to the source checkout for the Lemline version you run |
| `LEMLINE_GATEWAY` | Gateway hostname and gRPC port, for example `gateway.example.com:9000` |
| `LEMLINE_GATEWAY_CA` | PEM file for the CA that signed the gateway certificate |
| `LEMLINE_NAMESPACE` | A namespace containing a published definition and allowed by your token |
| `LEMLINE_TOKEN` | A valid bearer token from the configured issuer. With the default claims, include the namespace in the `lemline_namespaces` string. |

```bash
grpcurl -max-time 5 -cacert "$LEMLINE_GATEWAY_CA" \
  -import-path "$LEMLINE_SOURCE/lemline-runner-gateway/src/main/proto" \
  -proto lemline/gateway/v1/gateway.proto \
  -H "authorization: Bearer $LEMLINE_TOKEN" \
  -d "$(jq -nc --arg namespace "$LEMLINE_NAMESPACE" '{namespace: $namespace, pageSize: 1}')" \
  "$LEMLINE_GATEWAY" lemline.gateway.v1.GatewayService/ListDefinitions
```

The response includes one definition from the requested namespace. For `UNAUTHENTICATED`, check the token's issuer, expiry and signing key. For an authorization error, check the token's namespace claim and the gateway's namespace configuration. Starting instances requires the `lemline.start` scope. Live watch calls require `lemline.watch`.

See [gRPC Gateway Reference](lemline-ref-gateway.md) for API options, scopes and status codes.
