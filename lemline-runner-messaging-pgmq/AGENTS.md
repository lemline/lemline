<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-messaging-pgmq

PGMQ (PostgreSQL Message Queue) connector for SmallRye Reactive Messaging.

## Key Files

| File | Description |
|------|-------------|
| `PgmqClient.kt` | Low-level PGMQ queue operations |
| `PgmqMessage.kt` | PGMQ message wrapper |
| `connector/` | SmallRye Reactive Messaging connector implementation |
| `config/` | PGMQ-specific configuration |

## For AI Agents

### Working In This Directory
- Custom SmallRye connector - follows SmallRye Reactive Messaging SPI
- PostgreSQL-only (no MySQL/H2 support needed)
- Uses PGMQ extension for queue operations
- Schema changes require Flyway migrations in `db/migration/postgresql/` only

### Testing
```bash
./gradlew :lemline-runner-messaging-pgmq:test
```

<!-- MANUAL: -->
