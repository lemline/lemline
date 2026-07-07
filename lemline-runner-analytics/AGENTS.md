# AGENTS.md

Module-specific guidance for AI coding agents working in `lemline-runner-analytics`. The repository-wide rules in the root [`AGENTS.md`](../AGENTS.md) still apply (Kotlin coroutines, no Mutiny, no Hibernate/Panache, Flyway-only schema changes, `IDV7` IDs, domain/transport decoupling).

## Purpose

Sink-only lifecycle analytics ingestion. Consumes lifecycle `CloudEvent`s off the `lifecycleevents-in` channel and persists them, deduplicated, into a dedicated analytics datasource. It emits **no** downstream messages — this is the read/reporting side feeding dashboards and watch/query paths, decoupled from the orchestration hot path.

## Build & Test

```bash
./gradlew :lemline-runner-analytics:build
./gradlew :lemline-runner-analytics:test 2>&1 | tee /tmp/lemline-analytics-test.log
# single test (Kotest/JUnit5):
./gradlew :lemline-runner-analytics:test --tests "*LifecycleAnalyticsServiceTest*" 2>&1 | tee /tmp/lemline-analytics-svc.log
```

## Ingestion Flow

```
lifecycleevents-in (String payload)
  → LifecycleAnalyticsSubscriber   (MessageSubscriber<CloudEvent>, concurrency-bounded)
  → LifecycleAnalyticsHandler      (deserialize via CloudEventService → handle → returns null)
  → LifecycleAnalyticsService.ingest  (CloudEvent → LifecycleAnalyticsModel row)
  → LifecycleAnalyticsRepository.insert  (idempotent INSERT, returns INSERTED|DUPLICATE)
```

`LifecycleAnalyticsHandler` extends the shared `MessageHandler<CloudEvent>` contract but `handle()` returns `null` (no emission). The `serialize`/`emit`/`deriveIdempotentKey` overrides intentionally `throw UnsupportedOperationException` — they are never reached. Keep them that way; do not wire downstream emission into this module.

## Dual-Database Support (the key structural fact)

Unlike most runner feature modules, analytics is **not** single-datasource. `config/AnalyticsManager` (implements `AnalyticsConfig` from `lemline-runner-common`) resolves between two datasources/Flyway instances at runtime based on `lemline.analytics.type`:

- `AnalyticsType.H2` → `@DataSource("analytics")` / `@FlywayDataSource("analytics")`
- `AnalyticsType.POSTGRESQL` → `@DataSource("analytics-postgresql")` / `@FlywayDataSource("analytics-postgresql")`

Consequences for any SQL or schema change:

- Migrations are **per-database**: `src/main/resources/db/migration/analytics/{h2,postgresql}/`. Add/version both, keeping versions (`V00x__`) in lockstep.
- Type-specific SQL branches live in the repository, keyed on `analyticsConfig.analyticsType`:
  - PostgreSQL stores `payload` as `JSONB` (`CAST(? AS JSONB)`); H2 stores it as a plain string (`?`).
  - PostgreSQL qualifies the table with the configured schema (`"schema"."table"`); H2 uses the bare table name.
- Idempotent insert is delegated to `analyticsConfig.insertIgnoreInto(...)` so each dialect emits its correct ignore-on-conflict form. Preserve this abstraction rather than hardcoding `ON CONFLICT`.

## Idempotency & Mapping Invariants

- Deduplication is enforced by the DB unique constraint `(source, event_id)`. `insert` returns `DUPLICATE` when `executeUpdate() == 0`. Keep ingest idempotent — re-delivered events must not double-insert.
- `LifecycleAnalyticsService.toRow` maps CloudEvent standard attributes plus four Lemline extensions: `lemlineworkflowid`, `lemlineworkflownamespace`, `lemlineworkflowname`, `lemlineworkflowversion`. These columns drive dashboard/watch queries — **do not rename or drop them** without coordinating the consuming query side.
- `lemlineworkflowid` is parsed to `UUID`; malformed values are logged and stored as `NULL` (never throw). The full original event is preserved in `payload` regardless.
- Table/schema identifiers are validated against `^[A-Za-z_][A-Za-z0-9_]*$` and quoted — keep this guard when touching SQL string assembly (it is the injection boundary for the configurable schema name).

## Configuration & Startup

- Property keys are centralized in `lemline-runner-common` (`LemlineConfigKeys`); **defaults** live in `config/AnalyticsConfigConstants`. Don't scatter literal keys.
- The whole module is gated by `lemline.messaging.lifecycle-events.consumer.enabled` (**default `false`**). When disabled, the subscriber is inert and startup hooks no-op.
- Startup hooks (CDI `@Observes StartupEvent`, ordered by `@Priority`):
  - `AnalyticsStartupValidator` (`@Priority(0)`): verifies analytics DB connectivity, fails fast with an actionable banner.
  - `AnalyticsMigration` (`@Priority(1)`): runs Flyway on the resolved datasource when `lemline.analytics.migrate-at-start` is true (default `true`).

## Dependencies

- Internal: `lemline-common`, `lemline-core`, `lemline-runner-common`, `lemline-runner-listeners` (the latter for `CloudEventService` de/serialization).
- External: Quarkus Arc/Flyway/JDBC-PostgreSQL, SmallRye Reactive Messaging (Kafka + RabbitMQ connectors), CloudEvents SDK (core + jackson-json), Micrometer.
