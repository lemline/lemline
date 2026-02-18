<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-failures

Dead letter storage for failed workflow instances. Terminal state - no outbox pattern.

## Key Files

| File | Description |
|------|-------------|
| `FailureRepository.kt` | SQL persistence for failure records |
| `FailureModel.kt` | Failure entity with error details |
| `FailureReasons.kt` | Failure reason enumeration |

## For AI Agents

### Working In This Directory
- **No outbox pattern** - failures are terminal, write-once
- This is the smallest feature module
- Schema changes require Flyway migrations in all 3 DB dialects

### Testing
```bash
./gradlew :lemline-runner-failures:test
```

<!-- MANUAL: -->
