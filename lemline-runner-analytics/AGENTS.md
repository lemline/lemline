<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-22 -->

# lemline-runner-analytics

Lifecycle analytics ingestion module. Consumes lifecycle CloudEvents and stores them in analytics PostgreSQL.

## Key Files

| File | Description |
|------|-------------|
| `LifecycleAnalyticsSubscriber.kt` | Reactive Messaging subscriber on `lifecycleevents-in` |
| `LifecycleAnalyticsHandler.kt` | CloudEvent deserialization + sink-only handling |
| `LifecycleAnalyticsService.kt` | Maps CloudEvents into analytics persistence rows |
| `LifecycleAnalyticsRepository.kt` | JDBC insert with deduplication (`ON CONFLICT DO NOTHING`) |
| `LifecycleAnalyticsModel.kt` | Analytics persistence model |
| `config/AnalyticsManager.kt` | Access to analytics datasource + Flyway bean |
| `config/AnalyticsStartupValidator.kt` | Startup connectivity validation |
| `config/AnalyticsMigration.kt` | Startup Flyway migration trigger |
| `src/main/resources/db/migration/analytics/postgresql/` | Analytics schema migrations |

## For AI Agents

### Working In This Directory
- This module is sink-only: it consumes lifecycle events and does not emit downstream messages.
- Analytics storage is PostgreSQL-specific (`analytics` datasource + JSONB payload column).
- Deduplication is `(source, event_id)` at DB level; preserve idempotent ingest semantics.
- Configuration keys live under `lemline.analytics.*` (see `AnalyticsConfigConstants`).
- Keep CloudEvent extension mapping stable (`lemlineworkflow*` extensions) to avoid breaking watch/query paths.

### Testing
```bash
./gradlew :lemline-runner-analytics:test
```

### Dependencies
- Internal: `lemline-common`, `lemline-core`, `lemline-runner-common`, `lemline-runner-listeners`
- External: Quarkus messaging, Flyway, PostgreSQL JDBC, CloudEvents SDK, Micrometer

<!-- MANUAL: -->
