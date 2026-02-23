# lemline-runner-gateway

gRPC ingress gateway for starting workflows and streaming workflow analytics.

## How this module is started

`lemline-runner-gateway` is not a standalone executable module.  
It is loaded by `lemline-runner`, and you start it with the CLI command:

```bash
gateway start
```

## Configuration

Gateway startup requires:

1. Runner database config (`lemline.database.*`) for gateway outbox persistence
2. Runner messaging config (`lemline.messaging.*`) to publish workflow start commands
3. Gateway config (`lemline.gateway.*`) for gRPC/TLS/security
4. Analytics config (`lemline.analytics.*`) for watch replay/tail

Example `.lemline.yaml` (authentication disabled):

```yaml
lemline:
  database:
    type: postgresql
    migrate-at-start: true
    baseline-on-migrate: false
    postgresql:
      host: localhost
      port: 5432
      database: lemline
      username: postgres
      password: postgres

  messaging:
    type: kafka
    kafka:
      brokers: localhost:9092

  gateway:
    enabled: true
    grpc:
      host: 0.0.0.0
      port: 9000
    cors:
      enabled: true
      origins: http://localhost:5173
      methods: GET,POST,OPTIONS
      headers: Accept,Authorization,Content-Type,Grpc-Timeout,X-Grpc-Web,X-User-Agent
    tls:
      enabled: false
    authentication:
      enabled: false
    watch:
      poll-interval-ms: 250
      batch-size: 256

  analytics:
    backend: postgresql
    migrate-at-start: true
    baseline-on-migrate: false
    postgresql:
      host: localhost
      port: 5432
      database: lemline_analytics
      username: postgres
      password: postgres
      schema: public
      table: lemline_lifecycle_events
```

Notes:

1. In this first example, `lemline.gateway.tls.enabled=false`, so gRPC runs in plaintext (development only).
2. In this first example, `lemline.gateway.authentication.enabled=false`, so JWT auth and authorization checks are disabled.
3. CORS configuration is now under `lemline.gateway.cors.*`; set `lemline.gateway.cors.enabled=false` to disable CORS headers.
4. For secure mode with TLS and JWT claims/scopes, see `Authentication and authorization` below.
5. `lemline.analytics.type=clickhouse` is currently not implemented in the gateway; use `postgresql`.
6. `gateway start` sets `lemline.gateway.enabled=true` automatically; keeping it in config is still fine.

## Start the gateway (JVM / Java)

Build:

```bash
./gradlew :lemline-runner:build
```

Run:

```bash
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar --config=/absolute/path/.lemline.yaml gateway start
```

Optional port override:

```bash
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar --config=/absolute/path/.lemline.yaml gateway start --port 9443
```

## Start the gateway (Native)

Build native binary:

```bash
./gradlew :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

For Linux binaries built on macOS, add `-Dquarkus.native.container-build=true`.

Run:

```bash
./lemline-runner/build/lemline-runner-*-runner --config=/absolute/path/.lemline.yaml gateway start
```

## Authentication and authorization

To enable authentication in a secure setup:

1. Set `lemline.gateway.tls.enabled=true`
2. Configure JWT verification:
   - `lemline.gateway.authentication.jwt.issuer`
   - `lemline.gateway.authentication.jwt.jwks-url`
3. Optionally customize claim names:
   - `lemline.gateway.authentication.claims.scope-field` (default: `scope`)
   - `lemline.gateway.authentication.claims.namespaces-field` (default: `lemline_namespaces`)

Security snippet:

```yaml
lemline:
  gateway:
    tls:
      enabled: true
      certificate: /path/to/server.crt
      private-key: /path/to/server.key
      client-auth: none
    authentication:
      enabled: true
      jwt:
        issuer: https://issuer.example.com/
        jwks-url: https://issuer.example.com/.well-known/jwks.json
      claims:
        scope-field: scope
        namespaces-field: lemline_namespaces
```

TLS validation rules:

1. When `lemline.gateway.tls.enabled=true`, `lemline.gateway.tls.certificate` and `lemline.gateway.tls.private-key` are required.
2. `lemline.gateway.tls.trust-store` is required only when `lemline.gateway.tls.client-auth` is `request` or `required`.
3. `lemline.gateway.authentication.enabled=true` requires `lemline.gateway.tls.enabled=true`.

Authorization requirements:

1. `StartWorkflow` requires scope `lemline.start`
2. `WatchWorkflow` requires scope `lemline.watch`
3. Namespace access is checked against the namespaces claim (`*` allows all)

Accepted claim formats for scopes/namespaces:

1. Space-separated string (example: `"lemline.start lemline.watch"`)
2. Comma-separated string (example: `"lemline.start,lemline.watch"`)
3. Array/list claim values

## Config file resolution order

If `--config` is not provided, Lemline resolves config in this order:

1. `LEMLINE_CONFIG` environment variable
2. `./.lemline.yaml`
3. `~/.config/lemline/config.yaml`
4. `~/.lemline.yaml`
