<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-waits

Timer-based wait task handling. Persists workflow state until a delay expires.

## Key Files

| File | Description |
|------|-------------|
| `WaitService.kt` | Wait creation and resumption logic |
| `WaitRepository.kt` | SQL persistence with `FOR UPDATE SKIP LOCKED` |
| `WaitModel.kt` | Wait entity with delay and serialized state |
| `WaitOutbox.kt` | Outbox relay for due waits |
| `WaitCleaner.kt` | Cleanup of completed waits |
| `WaitConfig.kt` | Wait feature configuration |

## For AI Agents

### Working In This Directory
- Follows standard outbox pattern
- Wait stores full workflow state + delay timestamp
- Outbox polls for due waits and resumes execution
- Schema changes require Flyway migrations in all 3 DB dialects
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-waits:test
```

<!-- MANUAL: -->
