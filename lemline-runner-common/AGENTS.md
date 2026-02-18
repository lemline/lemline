<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-common

Shared infrastructure for all runner feature modules. Contains base classes for outbox, repository, cleaner, and messaging patterns.

## Key Directories

| Directory | Description |
|-----------|-------------|
| `outbox/` | `AbstractOutbox<T>` - base outbox relay with backoff and batch processing |
| `cleaner/` | `AbstractCleaner<T>` - base cleaner for purging old SENT records |
| `repositories/` | Repository base classes and SQL helpers |
| `repositories/helpers/` | SQL dialect helpers for PostgreSQL/MySQL/H2 |
| `repositories/ops/` | Common repository operations |
| `repositories/with/` | `WithOutbox`, `WithUUID` interfaces |
| `models/` | Shared model types |
| `messaging/` | Message serialization and channel utilities |
| `config/` | Shared configuration interfaces |
| `activities/` | Activity runner infrastructure |
| `scheduled/` | Scheduled task base classes |
| `starters/` | Workflow starter utilities |

## For AI Agents

### Working In This Directory
- This is the **foundation** for all `lemline-runner-*` feature modules
- Changes affect all feature modules - test broadly
- Repository methods must be `suspend` functions
- SQL must work across PostgreSQL, MySQL, and H2
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-common:test
```

### Dependencies
- Internal: `lemline-common`, `lemline-core`
- External: Quarkus, Agroal (connection pool), Flyway

<!-- MANUAL: -->
