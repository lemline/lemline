<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-retries

Retry handling with exponential backoff for failed tasks.

## Key Files

| File | Description |
|------|-------------|
| `RetryService.kt` | Retry scheduling and execution |
| `RetryRepository.kt` | SQL persistence with `FOR UPDATE SKIP LOCKED` |
| `RetryModel.kt` | Retry entity with attempt count and backoff |
| `RetryOutbox.kt` | Outbox relay for due retries |
| `RetryCleaner.kt` | Cleanup of completed retries |
| `RetryConfig.kt` | Retry feature configuration |

## For AI Agents

### Working In This Directory
- Follows standard outbox pattern
- Exponential backoff calculated from attempt count
- Max attempts configurable per task
- Schema changes require Flyway migrations in all 3 DB dialects
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-retries:test
```

<!-- MANUAL: -->
