---
title: Lifecycle Analytics Reference
---

# Lifecycle Analytics Reference

Lemline stores lifecycle CloudEvents in SQLite or PostgreSQL for historical queries and live watches through the gateway. Use SQLite for a local analytics file on a single host.

## What Lifecycle Analytics Does

When enabled, Lemline consumes lifecycle events and writes them to a dedicated analytics table.

- Input: lifecycle CloudEvents from the `lifecycleevents-in` channel
- Destination: the `lemline_lifecycle_events` table in the configured analytics database
- Deduplication key: `(source, event_id)`
- Payload: the complete serialized CloudEvent, including extensions and data, in `payload`

## Required Enablement

Lifecycle analytics requires both:

1. Lifecycle event production enabled
2. Lifecycle event consumer enabled

### Configure PostgreSQL

<!-- workflow-example: data -->
```yaml
lemline:
  messaging:
    type: kafka
    lifecycleevents:
      producer:
        enabled: true
      consumer:
        enabled: true
        concurrency: 64
    kafka:
      lifecycleevents:
        topic: lemline-lifecycle-events

  analytics:
    type: postgresql
    migrate-at-start: true
    baseline-on-migrate: false
    postgresql:
      host: localhost
      port: 5432
      database: lemline_analytics
      username: postgres
      password: postgres
      schema: lemline
```

Use a database dedicated to analytics. Its Flyway history is separate from the workflow database's, so pointing analytics at the workflow database and schema makes its first migration find a non-empty schema and stop the runner at startup (unless `baseline-on-migrate` is enabled).

`lemline listen` enables both the lifecycle event producer and consumer automatically. Configure the flags explicitly when running other processes that emit or ingest lifecycle events.

### Configure SQLite

Add this analytics block to the runner configuration alongside your workflow database and messaging settings. Use the JVM package for SQLite analytics.

<!-- workflow-example: data -->
```yaml
lemline:
  analytics:
    type: sqlite
    migrate-at-start: true
    sqlite:
      path: /var/lib/lemline/analytics.db
```

Set `type: sqlite` explicitly. If you omit `sqlite.path`, Lemline uses `analytics.db`. It resolves relative paths against the working directory of each process. Use a dedicated local file for analytics with an existing, writable parent directory. Use an absolute path when the runner and gateway start from different directories.

Start ingestion with `lemline --config lemline.yaml listen`. On the same host, configure the [gateway](lemline-ref-gateway.md) to use the same file and start it with `lemline --config lemline.yaml gateway start`. The gateway serves saved history with its analytics consumer disabled. When `migrate-at-start` is enabled, either process can prepare a new SQLite file. They take turns through an `analytics.db.migration.lock` file created next to the database; leave it in place. A gateway that creates a new file logs a warning, because a wrong path would otherwise show an empty history.

SQLite uses write-ahead logging (WAL), so gateway reads can continue while ingestion writes. SQLite serializes writes. Each connection enables foreign keys, uses `synchronous=FULL`, and waits up to five seconds for a database lock.

## Configuration Keys

| Property | Default | Description |
|----------|---------|-------------|
| `lemline.messaging.lifecycleevents.consumer.enabled` | `false` | Enables lifecycle events consumer used for analytics ingestion |
| `lemline.messaging.<broker>.lifecycleevents.consumer.concurrency` | `64` | Max concurrent lifecycle analytics message handling (`lemline.messaging.lifecycleevents.consumer.concurrency` with in-memory messaging) |
| `lemline.analytics.type` | `h2` | Analytics backend. Choose `sqlite` or `postgresql`. `h2` is available in Lemline's test suite. |
| `lemline.analytics.sqlite.path` | `analytics.db` | Local SQLite database file. Used with `type: sqlite`. |
| `lemline.analytics.migrate-at-start` | `true` | Runs analytics Flyway migration on startup |
| `lemline.analytics.baseline-on-migrate` | `false` | Enables Flyway baseline behavior for PostgreSQL analytics |
| `lemline.analytics.postgresql.host` | `localhost` | Analytics PostgreSQL host |
| `lemline.analytics.postgresql.port` | `5432` | Analytics PostgreSQL port |
| `lemline.analytics.postgresql.database` | `lemline` | Analytics PostgreSQL database name; use a database separate from the workflow database |
| `lemline.analytics.postgresql.username` | `postgres` | Analytics PostgreSQL username |
| `lemline.analytics.postgresql.password` | `postgres` | Analytics PostgreSQL password |
| `lemline.analytics.postgresql.schema` | `lemline` | Target schema for lifecycle analytics table |
| `lemline.messaging.lifecycleevents.producer.enabled` | `false` | Enables emitting lifecycle events into the messaging layer |

## Analytics Table Shape

The table is `lemline_lifecycle_events` in SQLite and `lemline.lemline_lifecycle_events` by default in PostgreSQL.

Important columns:

- Event identity: `event_id`, `source`, `type`, `specversion`
- CloudEvent optional metadata: `subject`, `event_time`, `datacontenttype`, `dataschema`
- Workflow extensions: `lemline_workflow_id`, `lemline_workflow_namespace`, `lemline_workflow_name`, `lemline_workflow_version`
- Full payload: `payload` (JSON text in SQLite, `JSONB` in PostgreSQL)
- Ingestion timestamp: `ingested_at`

SQLite stores projected workflow IDs as lowercase UUID text and `event_time` and `ingested_at` as signed Unix epoch microseconds in UTC. A missing event time is SQL `NULL`. The original CloudEvent in `payload` retains its timestamp precision.

On every backend, `lemline_workflow_id` is filled only when the `lemlineworkflowid` extension is a complete UUID such as `01890a5d-ac96-774b-bcce-b302099a8057`, in either case. Lemline stores an event with any other value, logs a warning, and leaves the column `NULL`, so the gateway does not attach that event to an instance.

## Troubleshooting

- For SQLite startup errors, verify that the configured path names a local file and that the process can write to both the file and its parent directory. If you disabled `migrate-at-start`, start against an already migrated file.
- For PostgreSQL connectivity errors, verify `lemline.analytics.postgresql.*` and PostgreSQL availability.
- If no rows are inserted, verify both producer and consumer are enabled:
  - `lemline.messaging.lifecycleevents.producer.enabled=true`
  - `lemline.messaging.lifecycleevents.consumer.enabled=true`
- If rows are missing unexpectedly, check deduplication collisions on `(source, event_id)`.
