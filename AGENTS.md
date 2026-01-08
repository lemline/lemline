# AGENTS.md

Guidelines for AI coding agents working in the Lemline repository.

**Generated:** 2026-01-02  
**Branch:** main

## Overview

Lemline: Event-driven workflow orchestration runtime implementing Serverless Workflow DSL v1.0.  
Kotlin 2.2.10 + Java 17 + Quarkus 3.x. Stateless workers, dual-channel messaging, outbox pattern.

## Structure

```
lemline/
├── lemline-common/          # Shared utilities (IDV7, JSON, logging)
├── lemline-core/            # DSL parsing, execution engine, processors
├── lemline-runner/          # Quarkus runtime, messaging, CLI
├── lemline-runner-cli/      # Picocli CLI commands
├── lemline-runner-common/   # Shared infrastructure (outbox, cleaner, repos)
├── lemline-runner-*/        # Feature modules (waits, retries, parents, forks, schedules, listeners, definitions, failures)
├── lemline-docs/            # Writerside documentation
├── docs/adr/                # Architecture Decision Records
├── buildSrc/                # Convention plugins
└── docker/                  # Docker compose files for dev
```

## Where to Look

| Task                   | Location                                                | Notes                                            |
|------------------------|---------------------------------------------------------|--------------------------------------------------|
| Add workflow task type | `lemline-core/src/.../processors/`                      | Create processor + state, use core-dev skill     |
| Add runner feature     | Create `lemline-runner-{feature}/`                      | Follow outbox pattern, use runner-dev skill      |
| Modify messaging       | `lemline-runner/src/.../messaging/`                     | commands/ for execution, events/ for persistence |
| Add CLI command        | `lemline-runner-cli/src/.../cli/`                       | Picocli commands                                 |
| Database schema        | `lemline-runner-*/src/main/resources/db/migration/`     | Flyway, all 3 DBs                                |
| Configuration          | `lemline-runner/src/.../config/LemlineConfiguration.kt` | Quarkus config                                   |
| Shared infrastructure  | `lemline-runner-common/src/main/kotlin/`                | Outbox, cleaner, repos                           |

## Commands

```bash
# Build
./gradlew build                           # Full build
./gradlew :lemline-core:build             # Single module

# Test
./gradlew test                            # All tests
./gradlew :lemline-runner:test            # Single module
./gradlew test --tests "*RepositoryTest"  # Pattern match

# Format
./gradlew spotlessCheck                   # Check
./gradlew spotlessApply                   # Fix

# Dev mode
./gradlew :lemline-runner:quarkusDev      # Hot reload
```

## Code Style

### Formatting

- 4 spaces indent (2 for JSON/YAML)
- 120 char line length
- LF line endings, UTF-8
- `// SPDX-License-Identifier: BUSL-1.1` header required

### Naming

| Element   | Convention      | Example         |
|-----------|-----------------|-----------------|
| Classes   | PascalCase      | `NodeProcessor` |
| Functions | camelCase       | `findById`      |
| Constants | SCREAMING_SNAKE | `MAX_RETRIES`   |

### Async

```kotlin
// CORRECT: suspend functions with coroutines
suspend fun findByUUID(uuid: IDV7): Model?

// WRONG: Mutiny Uni
fun findByUUID(uuid: IDV7): Uni<Model?>
```

### IDs

All entities use `IDV7` (UUID v7) - time-sortable, globally unique.

## Anti-Patterns (This Project)

### NEVER

- **Mutiny (Uni/Multi)** - Use Kotlin coroutines with `suspend` functions
- **Hibernate ORM/Panache** - Use native SQL with repositories
- **Type suppression** - No `as any`, `@ts-ignore`, empty catch blocks
- **Database-specific SQL** without variants for PostgreSQL, MySQL, H2
- **Skip migrations** - All schema changes via Flyway

### SQL Warning

```kotlin
// WRONG: NULL comparison always fails
WHERE col = NULL

// CORRECT: Use null-safe equals
    WHERE col IS NOT DISTINCT FROM $1--PostgreSQL
WHERE col <= > $1--MySQL
```

### TODO Items Found

- `scope.kt`: "Current implementation does not follow spec"
- `HttpCall.kt`: "authorization_code grant is not yet supported"
- `InstanceStartCommand.kt`: "This should ideally be transactional"

## Complexity Hotspots

| File                      | Lines | Issue                                           |
|---------------------------|-------|-------------------------------------------------|
| `FullOrchestrator.kt`     | 1024  | Deep nesting, long methods for event collection |
| `NodeProcessor.kt`        | 680   | Complex expression evaluation                   |
| `HttpCall.kt`             | 640   | OAuth2 state machine, incomplete grant types    |
| `WorkflowState.kt`        | 629   | Sealed class hierarchy needs architectural docs |
| `LemlineConfiguration.kt` | 536   | Extensive config mapping                        |

## Module Patterns

### Feature Modules (lemline-runner-*)

Each follows consistent outbox pattern:

- `*Service.kt` - Business logic
- `*Model.kt` - Entity with `WithOutbox` interface
- `*Repository.kt` - SQL operations with `FOR UPDATE SKIP LOCKED`
- `*Outbox.kt` - Extends `AbstractOutbox<T>`
- `*Cleaner.kt` - Extends `AbstractCleaner<T>`

### Deviations

- `lemline-runner-definitions` - Pure CRUD, no outbox (definitions are static)
- `lemline-runner-failures` - Dead letter storage, no outbox (failures are terminal)
- `lemline-runner-listeners` - Multiple specialized outboxes

## Testing

### lemline-core

Kotest + coroutines test:

```kotlin
@Test
fun testWorkflow() = runTest {
        val processor = getWorkflowProcessor(yaml, input)
        processor.run()
        assertEquals(expected, processor.output)
    }
```

### lemline-runner

Kotest + `@QuarkusTest` + Testcontainers:

```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class RepositoryTest : FunSpec({
    test("should find entity") {
        val result = repository.findById(id)
        assertNotNull(result)
    }
})
```

**Test all 3 databases** when touching persistence layer.

### Test Output Handling (AI Agents)

**Always capture test output to files** - never rely on `grep | tail` pipelines that may miss important information.

```bash
# CORRECT: Capture full output, then analyze
./gradlew :lemline-runner-listeners:test --tests "*ListenerRepository*" 2>&1 | tee /tmp/lemline-listeners-repo.log

# Then iterate on analysis without rerunning:
grep -E "FAILED|ERROR" /tmp/lemline-listeners-repo.log
grep -i "exception" /tmp/lemline-listeners-repo.log | head -30
tail -100 /tmp/lemline-listeners-repo.log

# WRONG: Direct pipeline that may lose context
./gradlew :module:test 2>&1 | grep -E "PASSED|FAILED" | tail -5
```

**File naming convention**: `/tmp/lemline-<module-short>-<test-pattern>.log`

- `/tmp/lemline-core-dataflow.log`
- `/tmp/lemline-runner-outbox.log`
- `/tmp/lemline-listeners-repo.log`

**Cleaning**: Manual.

**Why this matters**:

- Test runs are expensive (Gradle startup, compilation, Testcontainers)
- `tee` gives real-time output AND persistent file
- Reanalyze without rerunning when grep pattern was too narrow

## Key Files

### Core Execution

- `lemline-core/src/.../orchestrator/StepByStepOrchestrator.kt` - Step execution
- `lemline-core/src/.../orchestrator/FullOrchestrator.kt` - Full execution (tests)
- `lemline-core/src/.../processors/NodeProcessor.kt` - Processor interface

### Runner Infrastructure

- `lemline-runner/src/.../messaging/commands/WorkflowCommandHandler.kt` - Command handling
- `lemline-runner/src/.../messaging/events/WorkflowEventHandler.kt` - Event routing
- `lemline-runner-common/src/.../outbox/AbstractOutbox.kt` - Outbox base class

### Entry Points

- `lemline-runner/src/.../LemlineApplication.kt` - CLI bootstrap (pre-Quarkus)
- `lemline-runner-cli/src/.../MainCommand.kt` - Picocli top command

## Skills (Use These)

For detailed development guidance, invoke skills:

| Skill        | When to Use                                                              |
|--------------|--------------------------------------------------------------------------|
| `core-dev`   | Working on lemline-core (processors, orchestrators, states, expressions) |
| `runner-dev` | Working on lemline-runner-* (messaging, outbox, repositories, CLI)       |

Skills provide file references, code patterns, and critical rules.

## Architecture Notes

### Dual-Channel Design

- **Commands channel** (`commands-in/out`): High-throughput, stateless execution
- **Events channel** (`events-out`): Durable operations requiring DB (waits, retries, parents)

### State Compression

Entire workflow state serialized into `InstanceMessage` - enables stateless workers.

### Exception-Driven Control

- Normal step: Returns `NextStepInfo`
- Wait needed: Throws `WaitStartedException`
- Retry needed: Throws `TaskRetriedException`
- Child workflow: Throws `RunWorkflowStartedException`

### Structural Deviations (Non-Standard)

- No root `build.gradle.kts` - all via buildSrc convention plugins
- Extremely granular feature modules (9 tiny outbox modules)
- Separated CLI module from main runner
- Dual "common" modules (`lemline-common` + `lemline-runner-common`)

## Language

Always use English in code, comments, and documentation.
