---
title: Lifecycle Analytics Reference
---

# Lifecycle Analytics Reference

This reference covers lifecycle analytics ingestion in Lemline: how lifecycle CloudEvents are persisted to an analytics PostgreSQL database, which properties control it, and what data is stored.

## What Lifecycle Analytics Does

When enabled, Lemline consumes lifecycle events and writes them to a dedicated analytics table.

- Input: lifecycle CloudEvents from the `lifecycleevents-in` channel
- Destination: analytics PostgreSQL table (default `public.lemline_lifecycle_events`)
- Deduplication key: `(source, event_id)`
- Payload: serialized CloudEvent JSON in `payload` (`JSONB`)

## Required Enablement

Lifecycle analytics requires both:

1. Lifecycle event production enabled
2. Lifecycle event consumer enabled

Example:

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

## Configuration Keys

| Property | Default | Description |
|----------|---------|-------------|
| `lemline.messaging.lifecycleevents.consumer.enabled` | `false` | Enables lifecycle events consumer used for analytics ingestion |
| `lemline.messaging.lifecycleevents.consumer.concurrency` | `64` | Max concurrent lifecycle analytics message handling |
| `lemline.analytics.migrate-at-start` | `true` | Runs analytics Flyway migration on startup |
| `lemline.analytics.baseline-on-migrate` | `false` | Enables Flyway baseline behavior for analytics DB |
| `lemline.analytics.postgresql.host` | `localhost` | Analytics PostgreSQL host |
| `lemline.analytics.postgresql.port` | `5432` | Analytics PostgreSQL port |
| `lemline.analytics.postgresql.database` | `lemline_analytics` | Analytics PostgreSQL database name |
| `lemline.analytics.postgresql.username` | `postgres` | Analytics PostgreSQL username |
| `lemline.analytics.postgresql.password` | `postgres` | Analytics PostgreSQL password |
| `lemline.analytics.postgresql.schema` | `public` | Target schema for lifecycle analytics table |
| `lemline.analytics.postgresql.table` | `lemline_lifecycle_events` | Target table for lifecycle analytics rows |
| `lemline.messaging.lifecycleevents.producer.enabled` | `false` | Enables emitting lifecycle events into the messaging layer |

## Analytics Table Shape

Default table: `public.lemline_lifecycle_events`

Important columns:

- Event identity: `event_id`, `source`, `type`, `specversion`
- CloudEvent optional metadata: `subject`, `event_time`, `datacontenttype`, `dataschema`
- Workflow extensions: `lemline_workflow_id`, `lemline_workflow_namespace`, `lemline_workflow_name`, `lemline_workflow_version`
- Full payload: `payload` (`JSONB`)
- Ingestion timestamp: `ingested_at`

## Troubleshooting

- If startup fails with analytics connectivity errors, verify `lemline.analytics.postgresql.*` and PostgreSQL availability.
- If no rows are inserted, verify both producer and consumer are enabled:
  - `lemline.messaging.lifecycleevents.producer.enabled=true`
  - `lemline.messaging.lifecycleevents.consumer.enabled=true`
- If rows are missing unexpectedly, check deduplication collisions on `(source, event_id)`.
