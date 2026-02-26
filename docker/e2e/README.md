# Lemline End-to-End Docker Environment

This setup starts a full local E2E environment with:

- PostgreSQL (`lemline`)
- PGMQ messaging on PostgreSQL (no Kafka, no ZooKeeper)
- Automatic workflow definition seeding at startup
- Lemline worker (`listen`)
- Lemline gateway (`gateway start`)
- gRPC-Web proxy (Envoy)
- Lemline dashboard

## Prerequisites

- Docker Engine + Docker Compose
- `mkcert` (for local TLS certificates trusted by browsers)

Install `mkcert` (examples):

- macOS: `brew install mkcert`
- Windows: `choco install mkcert` or `winget install FiloSottile.mkcert`
- Linux: use your distro package manager or binary release from the mkcert project

One-time trust setup on each machine:

```bash
mkcert -install
```

Generate local certs (from repository root) in docker/e2e/certs:

```bash
mkcert -cert-file docker/e2e/certs/localhost.pem \
  -key-file docker/e2e/certs/localhost-key.pem \
  localhost 127.0.0.1
```

## TLS Troubleshooting

If the gateway cannot start TLS or local cert trust is broken:

1. Reinstall trust and regenerate certificates from repository root:

```bash
mkcert -install
mkcert -cert-file docker/e2e/certs/localhost.pem \
  -key-file docker/e2e/certs/localhost-key.pem \
  localhost 127.0.0.1
```

2. Restart the gateway so it reloads the new certificate:

```bash
docker compose -f docker/e2e/docker-compose.yml restart gateway
```

3. Verify TLS trust and HTTP/2 from terminal:

```bash
curl -v --http2 https://localhost:9443/q/health
# Or via HTTP metrics port:
curl http://localhost:9444/q/health/ready
```

Expected: TLS certificate verify is OK and ALPN negotiates `h2`.  
Note: `/q/health` over gRPC TLS port may return `404`; use the metrics port (`9444`) for health checks.

4. Fully quit and reopen your browser after `mkcert -install` (especially Safari).

## Start

From repository root:

```bash
docker compose -f docker/e2e/docker-compose.yml up --build -d
```

Notes:

- The first build can take several minutes (Gradle dependencies + compile).
- A bootstrap service (`runner-image`) builds `lemline-runner-e2e:local` once, then `worker`, `gateway`, and
  `seed-definitions` reuse it.
- During `worker` image build, the longest step is `./gradlew :lemline-runner:quarkusBuild`; it can be quiet for a while
  on first run.
- Runner Docker build tunes Gradle memory/workers by default (`Xmx=4g`, metaspace `1g`, workers `6`).

Check status:

```bash
docker compose -f docker/e2e/docker-compose.yml ps
```

## Endpoints

- Dashboard: http://localhost:5173
- gRPC-Web proxy (used by dashboard): http://localhost:9446
- Gateway gRPC (TLS): https://localhost:9443
- Gateway metrics + health: http://localhost:9444
- Worker metrics + health: http://localhost:9445
- PostgreSQL: localhost:5432

## Shared configuration

Both `worker` and `gateway` use the same config file:

- `docker/e2e/config/lemline.yaml`

Note: `lemline.gateway.enabled` is intentionally omitted.  
When started with `gateway start`, the CLI enables gateway mode automatically.

## Automatic definition seeding

At startup, `seed-definitions` loads all workflow YAML files from:

- `lemline-runner/src/test/resources/examples` (mounted as `/workflows` in container)

It runs:

```bash
lemline definition post -d /workflows -r -F
```

So definitions are inserted/updated automatically before `worker` and `gateway` start.

To re-run seeding manually:

```bash
docker compose -f docker/e2e/docker-compose.yml run --rm seed-definitions
```

If seeding fails, inspect logs:

```bash
docker compose -f docker/e2e/docker-compose.yml logs seed-definitions
```

## Start a sample instance

Start an instance:

```bash
docker compose -f docker/e2e/docker-compose.yml exec worker \
  java -jar /app/quarkus-app/quarkus-run.jar \
  --config=/config/lemline.yaml \
  instance start e2e hello 1.0.0 --input '{"from":"docker-e2e"}'
```

## Logs

```bash
docker compose -f docker/e2e/docker-compose.yml logs -f worker gateway
```

## Stop

```bash
docker compose -f docker/e2e/docker-compose.yml down
```

Clean state (remove DB volume):

```bash
docker compose -f docker/e2e/docker-compose.yml down -v
```
