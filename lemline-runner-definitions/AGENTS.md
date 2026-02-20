<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-runner-definitions

Workflow definition storage and caching. Pure CRUD module - no outbox pattern.

## Key Files

| File | Description |
|------|-------------|
| `DefinitionService.kt` | CRUD operations for workflow definitions |
| `DefinitionRepository.kt` | SQL persistence for definitions |
| `DefinitionModel.kt` | Definition entity model |
| `DefinitionConfig.kt` | Configuration for definition management |
| `DefinitionCacheSync.kt` | In-memory cache synchronization |
| `Definitions.kt` | Definition lookup utilities |

## For AI Agents

### Working In This Directory
- **No outbox pattern** - definitions are static, direct CRUD
- Schema changes require Flyway migrations in `src/main/resources/db/migration/{postgresql,mysql,h2}/`
- Use `runner-dev` skill for detailed guidance

### Testing
```bash
./gradlew :lemline-runner-definitions:test
```

<!-- MANUAL: -->
