# Lemline End-to-End Docker Environment

This setup starts a full local E2E environment with:

- PostgreSQL (`lemline` + `lemline_analytics`)
- PGMQ messaging on PostgreSQL (no Kafka, no ZooKeeper)
- Automatic workflow definition seeding at startup
- Lemline worker (`listen`)
- Lemline gateway (`gateway start`)
- Lemline dashboard

## Prerequisites

- Docker Engine + Docker Compose

## Start

From repository root:

```bash
docker compose -f docker/e2e/docker-compose.yml up --build -d
```

Notes:

- The first build can take several minutes (Gradle dependencies + compile).
- A bootstrap service (`runner-image`) builds `lemline-runner-e2e:local` once, then `worker`, `gateway`, and `seed-definitions` reuse it.
- During `worker` image build, the longest step is `./gradlew :lemline-runner:quarkusBuild`; it can be quiet for a while on first run.
- Runner Docker build tunes Gradle memory/workers by default (`Xmx=4g`, metaspace `1g`, workers `6`).

Check status:

```bash
docker compose -f docker/e2e/docker-compose.yml ps
```

## Endpoints

- Dashboard: http://localhost:5173
- Gateway (gRPC / gRPC-Web plaintext): http://localhost:9000
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
