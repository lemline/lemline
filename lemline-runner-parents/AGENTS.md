<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-parents

Parent-child workflow relationship tracking. Manages RunWorkflow task lifecycle.

## Key Files

| File | Description |
|------|-------------|
| `ParentService.kt` | Parent state persistence and child completion handling |
| `ParentRepository.kt` | SQL persistence for parent records |
| `ParentModel.kt` | Parent entity with serialized workflow state |
| `ParentCleaner.kt` | Cleanup of completed parent records |
| `ParentFeatureConfig.kt` | Parent feature configuration |

## For AI Agents

### Working In This Directory
- Follows standard outbox pattern
- Parent state is serialized and stored when child workflow starts
- On child completion, parent is restored and continues execution
- Schema changes require Flyway migrations in all 3 DB dialects
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-parents:test
```

<!-- MANUAL: -->
