<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-schedules

Cron-based workflow scheduling. Creates workflow instances on schedule.

## Key Files

| File | Description |
|------|-------------|
| `ScheduleService.kt` | Schedule management and cron evaluation |
| `ScheduleRepository.kt` | SQL persistence with `FOR UPDATE SKIP LOCKED` |
| `ScheduleModel.kt` | Schedule entity with cron expression |
| `ScheduleOutbox.kt` | Outbox relay for due schedules |
| `ScheduleCleaner.kt` | Cleanup of completed schedules |
| `ScheduleConfig.kt` | Schedule feature configuration |

## For AI Agents

### Working In This Directory
- Follows standard outbox pattern
- Schedules create new workflow instances (not resume existing)
- Cron expressions evaluated to determine next fire time
- Schema changes require Flyway migrations in all 3 DB dialects
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-schedules:test
```

<!-- MANUAL: -->
