<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-forks

Parallel branch execution tracking. Manages fork/join synchronization for workflow branches.

## Key Files

| File | Description |
|------|-------------|
| `ForkService.kt` | Fork creation and branch completion tracking |
| `ForkRepository.kt` | SQL persistence for fork state |
| `ForkModel.kt` | Fork entity (parent of branches) |
| `ForkBranchModel.kt` | Individual branch entity |
| `ForkBranchRepository.kt` | SQL persistence for branches |
| `ForkCleaner.kt` | Cleanup of completed forks |
| `ForkFeatureConfig.kt` | Fork feature configuration |

## For AI Agents

### Working In This Directory
- Follows standard outbox pattern (see root `AGENTS.md`)
- Fork tracks multiple branches; join waits for all to complete
- Schema changes require Flyway migrations in all 3 DB dialects
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-forks:test
```

<!-- MANUAL: -->
