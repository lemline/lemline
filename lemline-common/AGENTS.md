<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-18 -->

# lemline-common

Shared utilities and foundational types used across all Lemline modules.

## Key Files

| File/Directory | Description |
|----------------|-------------|
| `Logger.kt` | Structured logging utilities |
| `ids/` | `IDV7` - UUID v7 time-sortable identifiers |
| `json/` | Jackson JSON serialization helpers |
| `flexible/` | Flexible type wrappers for DSL values |
| `values/` | Value types and domain primitives |
| `logger/` | Logging infrastructure |

## For AI Agents

### Working In This Directory
- Changes here affect **every module** - test broadly after modifications
- `IDV7` is the universal ID type - never use raw UUIDs
- JSON helpers must remain database-agnostic

### Testing
```bash
./gradlew :lemline-common:test
```

### Dependencies
- No internal dependencies (leaf module)
- Jackson for JSON, SLF4J for logging

<!-- MANUAL: -->
