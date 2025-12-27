---
name: runner-dev
description: Development guide for the lemline-runner ecosystem. Use when working with the modular runner architecture (lemline-runner-* modules), messaging (commands/events channels), feature modules (waits, retries, parents, schedules, listeners, forks, failures), shared infrastructure (lemline-runner-common), CLI commands, configuration, repositories with Kotlin coroutines, or Flyway migrations. Covers the dual-channel architecture, outbox pattern, and production best practices for the Quarkus/Kotlin runtime.
---

# Lemline Runner Development Guide

## Purpose

Guide development across the **modular lemline-runner ecosystem** - a collection of feature-focused modules built on shared infrastructure. The main `lemline-runner` module provides messaging, CLI, and configuration, while feature modules (`lemline-runner-*`) implement specific workflow capabilities.

**Core Documentation:**

- [Main Runner README](lemline-runner/README.md) - Configuration, database, messaging setup
- [CLI Commands](lemline-runner/docs/runner-cli.md)
- [Configuration](lemline-runner/docs/runner-configuration.md)
- [Messaging Architecture](lemline-runner/docs/runner-messaging.md)
- [Database Tables](lemline-runner/docs/runner-tables.md)
- [Repositories Guide](lemline-runner/docs/runner-repositories-guide.md)

---

## Module Architecture

### Core Infrastructure

| Module | Purpose |
|--------|---------|
| `lemline-runner-common` | Shared infrastructure: outbox pattern, cleaner pattern, repository abstractions, model interfaces |
| `lemline-runner` | Main runtime: messaging (commands/events), CLI, configuration, activity runners |

### Feature Modules

| Module | Purpose | Tables |
|--------|---------|--------|
| `lemline-runner-definitions` | Workflow definition storage and cache sync | `lemline_definitions` |
| `lemline-runner-waits` | Wait/sleep task implementation | `lemline_waits` |
| `lemline-runner-retries` | Task retry scheduling with exponential backoff | `lemline_retries` |
| `lemline-runner-parents` | Parent-child workflow relationships (run task) | `lemline_parents` |
| `lemline-runner-forks` | Parallel branch execution (fork task) | `lemline_forks`, `lemline_fork_branches` |
| `lemline-runner-schedules` | Scheduled workflow execution (cron/interval/after) | `lemline_schedules` |
| `lemline-runner-listeners` | CloudEvent listeners (listen task) | `lemline_listeners`, `lemline_listener_events` |
| `lemline-runner-failures` | Failed workflow tracking and dead letter storage | `lemline_failures` |

**Each feature module has its own README.md with detailed architecture, file reference, and usage patterns.**

---

## Quick Reference

### If you need to work on...

**A Specific Feature:**

Read the module's README first:
- **Wait task** → [lemline-runner-waits/README.md](lemline-runner-waits/README.md)
- **Retry logic** → [lemline-runner-retries/README.md](lemline-runner-retries/README.md)
- **Parent-child workflows** → [lemline-runner-parents/README.md](lemline-runner-parents/README.md)
- **Fork/parallel execution** → [lemline-runner-forks/README.md](lemline-runner-forks/README.md)
- **Scheduled workflows** → [lemline-runner-schedules/README.md](lemline-runner-schedules/README.md)
- **CloudEvent listeners** → [lemline-runner-listeners/README.md](lemline-runner-listeners/README.md)
- **Failure tracking** → [lemline-runner-failures/README.md](lemline-runner-failures/README.md)
- **Definition storage** → [lemline-runner-definitions/README.md](lemline-runner-definitions/README.md)

**Messaging:**

First, read [runner-messaging.md](lemline-runner/docs/runner-messaging.md)

- **Add a new command/event type** →
  Modify [WorkflowState.kt](lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt)
- **Update commands behavior** →
  Modify [WorkflowCommandHandler.kt](lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt)
- **Update events behavior** →
  Modify [WorkflowEventHandler.kt](lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)

**Shared Infrastructure:**

Read [lemline-runner-common/README.md](lemline-runner-common/README.md) first

- **Add new outbox processor** → Extend `AbstractOutbox<T>` in lemline-runner-common
- **Add new cleaner** → Extend `AbstractCleaner<T>` in lemline-runner-common
- **Add repository operations** → Add to `lemline-runner-common/repositories/ops/`
- **Add model interface** → Create in `lemline-runner-common/models/`

**Configuration:**

First read [runner-configuration.md](lemline-runner/docs/runner-configuration.md)

- **Add/Update config property** →
  Modify [LemlineConfiguration.kt](lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt)
- **Add database/broker type** → Read [runner-configuration.md](lemline-runner/docs/runner-configuration.md)

**CLI:**

First read [runner-cli.md](lemline-runner/docs/runner-cli.md#adding-a-new-cli-command)

---

## Critical Rules

### ✅ ALWAYS Do This

1. **Use `suspend` functions** for all database operations (Kotlin coroutines, NOT Mutiny)
2. **Use native SQL** via repositories (NOT Hibernate ORM/Panache)
3. **Use Flyway migrations** for all schema changes
4. **Support all databases** (PostgreSQL, MySQL, H2) - use database-agnostic SQL
5. **Use `FOR UPDATE SKIP LOCKED`** for outbox queries to prevent double-processing
6. **Use IDV7** (UUID v7) for all entity IDs - time-sortable, globally unique
7. **Test with all supported databases** when touching persistence layer

### ❌ NEVER Do This

1. **Use Mutiny (Uni/Multi)** - use Kotlin coroutines with `suspend` functions instead
2. **Use Hibernate ORM/Panache** - use native SQL with repositories
3. **Block the event loop** - all I/O must be non-blocking
4. **Skip database migrations** - never modify tables directly
5. **Use database-specific SQL** without providing variants for all databases
6. **Commit sensitive data** (.env, credentials, API keys)

---

## Architecture Overview

### Modular Feature Design

```
┌────────────────────────────────────────────────────────────────────┐
│                      lemline-runner (core)                         │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐ │
│  │   Messaging      │  │   CLI Commands   │  │  Configuration   │ │
│  │ (commands/events)│  │   (Picocli)      │  │   (Quarkus)      │ │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
                                │
                ┌───────────────┴────────────────┐
                │  lemline-runner-common         │
                │  (shared infrastructure)       │
                │  • Outbox pattern              │
                │  • Cleaner pattern             │
                │  • Repository abstractions     │
                │  • Model interfaces            │
                └───────────────┬────────────────┘
                                │
        ┌───────────────────────┼────────────────────────┐
        │                       │                        │
┌───────▼──────┐   ┌───────────▼──────┐   ┌────────────▼─────┐
│ Feature      │   │ Feature           │   │ Feature          │
│ Modules      │   │ Modules           │   │ Modules          │
│              │   │                   │   │                  │
│ • waits      │   │ • schedules       │   │ • definitions    │
│ • retries    │   │ • listeners       │   │ • failures       │
│ • parents    │   │                   │   │                  │
│ • forks      │   │                   │   │                  │
└──────────────┘   └───────────────────┘   └──────────────────┘
```

### Dual-Channel Messaging

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  COMMANDS CHANNEL (high-throughput)                         │
│  commands ──► WorkflowCommandHandler ──► commands                           │
│       ▲                 │                                                   │
│       │                 │ (needs persistence)                               │
└───────│─────────────────│───────────────────────────────────────────────────┘
        │                 │
┌───────│─────────────────│───────────────────────────────────────────────────┐
│       │                 ▼              EVENTS CHANNEL                       │
│       │               events ──► WorkflowEventHandler ──► Feature Modules   │
│       │                                   │                                 │
│       │                         ┌─────────┴─────────┐                       │
│       │                         ▼                   ▼                       │
│       │              ┌─────────────────┐  ┌─────────────────┐              │
│       │              │ Feature Service │  │ Feature Service │              │
│       │              │ (e.g., Waits)   │  │ (e.g., Parents) │              │
│       │              └────────┬────────┘  └────────┬────────┘              │
│       │                       │                    │                       │
│       │                       ▼                    ▼                       │
│       │              ┌─────────────────┐  ┌─────────────────┐              │
│       │              │   DB Table      │  │   DB Table      │              │
│       │              │ (lemline_waits) │  │(lemline_parents)│              │
│       │              └────────┬────────┘  └─────────────────┘              │
│       │                       │                                            │
│       │                       ▼                                            │
│       │              ┌─────────────────┐                                   │
│       │              │  Outbox Relay   │                                   │
│       └──────────────│ (scheduled poll)│                                   │
│                      └─────────────────┘                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key principle:** State travels with messages. Database only used when necessary. Each feature is self-contained.

### Key Files

| Purpose           | File                                                                                                                        |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------|
| Step execution    | [StepByStepRunner.kt](lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt)                                |
| Command handling  | [WorkflowCommandHandler.kt](lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt) |
| Event handling    | [WorkflowEventHandler.kt](lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt)       |
| Message structure | [InstanceMessage.kt](lemline-runner-common/src/main/kotlin/com/lemline/runner/common/messaging/InstanceMessage.kt)         |
| Outbox base       | [AbstractOutbox.kt](lemline-runner-common/src/main/kotlin/com/lemline/runner/common/outbox/AbstractOutbox.kt)             |
| Cleaner base      | [AbstractCleaner.kt](lemline-runner-common/src/main/kotlin/com/lemline/runner/common/cleaner/AbstractCleaner.kt)          |

---

## Common Patterns

### Creating a New Feature Module

**1. Module Structure:**
```
lemline-runner-myfeature/
├── src/
│   ├── main/kotlin/com/lemline/runner/myfeature/
│   │   ├── MyFeatureService.kt         ← Business logic
│   │   ├── MyFeatureModel.kt           ← Database entity
│   │   ├── MyFeatureRepository.kt      ← Database operations
│   │   ├── MyFeatureOutbox.kt          ← Outbox processor (optional)
│   │   ├── MyFeatureCleaner.kt         ← Cleanup scheduler (optional)
│   │   └── MyFeatureConfig.kt          ← Configuration
│   ├── test/kotlin/                    ← Tests for all databases
│   └── testFixtures/kotlin/            ← Test utilities
├── build.gradle.kts
└── README.md                           ← Architecture and usage
```

**2. Dependencies in build.gradle.kts:**
```kotlin
dependencies {
    implementation(project(":lemline-common"))
    implementation(project(":lemline-core"))
    implementation(project(":lemline-runner-common"))  // Always required!
    // Add other feature modules if needed
}
```

**3. Model with Interfaces:**
```kotlin
// Compose behavior from lemline-runner-common interfaces
data class MyFeatureModel(
    override val id: IDV7,
    override val instanceMessage: InstanceMessage<MyEvent>,
    // Outbox fields
    override val outboxScheduledFor: Instant,
    override var outboxDelayedUntil: Instant? = outboxScheduledFor,
    override var outboxAttemptCount: Int = 0,
    override var outboxCompletedAt: Instant? = null,
    override var outboxFailedAt: Instant? = null,
    // ... other outbox fields
    // Cleanup field
    override var cleanupAfter: Instant? = null,
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup
```

**4. Repository:**
```kotlin
@ApplicationScoped
class MyFeatureRepository : Repository<MyFeatureModel>(),
    WithIdRepository<MyFeatureModel>,
    OutboxRepository<MyFeatureModel>,
    CleanerRepository<MyFeatureModel> {

    override suspend fun findByUUID(uuid: IDV7): MyFeatureModel? {
        // Use pool from base Repository
        return pool.withConnection { conn ->
            conn.preparedQuery("SELECT * FROM lemline_myfeature WHERE id = $1")
                .execute(Tuple.of(uuid.value))
                .awaitSuspending()
                .firstOrNull()
                ?.let { MyFeatureModel.fromRow(it) }
        }
    }

    // OutboxRepository provides findPendingWithLock() automatically
    // CleanerRepository provides findOldCompleted() automatically
}
```

**5. Service:**
```kotlin
@ApplicationScoped
class MyFeatureService @Inject constructor(
    private val repository: MyFeatureRepository,
    private val commandEmitter: CommandEmitter
) {
    suspend fun handleMyEventStarted(message: InstanceMessage<MyEventStarted>) {
        val model = MyFeatureModel.from(message)
        repository.insert(model)
    }
}
```

**6. Outbox (if needed):**
```kotlin
@ApplicationScoped
class MyFeatureOutbox @Inject constructor(
    private val repository: MyFeatureRepository,
    private val emitter: WorkflowCommandEmitter,
    private val config: MyFeatureConfig
) : AbstractOutbox<MyFeatureModel>(
    name = "MyFeature",
    config = config.outbox
) {
    override suspend fun findEntitiesToProcess(limit: Int) = 
        repository.findPendingWithLock(limit)

    override suspend fun process(entity: MyFeatureModel) {
        emitter.send(createResumeCommand(entity))
    }

    override suspend fun markCompleted(entity: MyFeatureModel) {
        repository.markCompleted(entity.id)
    }
}
```

**7. Register in WorkflowEventHandler:**
```kotlin
// In lemline-runner/src/.../messaging/events/WorkflowEventHandler.kt
when (val state = message.state) {
    is MyEventStarted -> myFeatureService.handleMyEventStarted(message)
    // ...
}
```

### Event Handling Pattern

```kotlin
// In feature module service
suspend fun handleMyEvent(message: InstanceMessage<MyEvent>) {
    val event = message.state

    // 1. Create model from event
    val model = MyFeatureModel.from(message, event)

    // 2. Persist to database
    repository.insert(model)

    // 3. If immediate resume needed (no outbox), emit command
    if (!needsDelay) {
        commandEmitter.send(createResumeCommand(message))
    }
    // Otherwise, outbox processor will handle it later
}
```

---

## Testing Patterns

### Multi-Database Repository Test

**Test all 3 databases: PostgreSQL, MySQL, H2**

```kotlin
// Base test with test logic
abstract class MyRepositoryTestBase : FunSpec({
    lateinit var repository: MyRepository

    test("should find by UUID") {
        val model = createTestModel()
        repository.insert(model)

        val found = repository.findByUUID(model.id)

        found shouldNotBe null
        found?.id shouldBe model.id
    }
})

// PostgreSQL test
@QuarkusTest
@TestProfile(PostgresProfile::class)
class MyRepositoryPostgresTest : MyRepositoryTestBase() {
    @Inject
    override lateinit var repository: MyRepository
}

// MySQL test
@QuarkusTest
@TestProfile(MySQLProfile::class)
class MyRepositoryMySQLTest : MyRepositoryTestBase() {
    @Inject
    override lateinit var repository: MyRepository
}

// H2 test
@QuarkusTest
@TestProfile(H2Profile::class)
class MyRepositoryH2Test : MyRepositoryTestBase() {
    @Inject
    override lateinit var repository: MyRepository
}
```

### Feature Service Test

```kotlin
@QuarkusTest
class MyFeatureServiceTest : FunSpec({

    @Inject
    lateinit var service: MyFeatureService

    @Inject
    lateinit var repository: MyFeatureRepository

    test("should handle event") {
        val message = createTestMessage()

        service.handleMyEvent(message)

        // Verify database state
        val stored = repository.findByUUID(message.workflowId)
        stored shouldNotBe null
    }
})
```

### Outbox Test

```kotlin
@QuarkusTest
class MyFeatureOutboxTest : FunSpec({

    @Inject
    lateinit var outbox: MyFeatureOutbox

    @Inject
    lateinit var repository: MyFeatureRepository

    test("should process pending entities") {
        // Insert pending entity
        val model = createPendingModel()
        repository.insert(model)

        // Process
        outbox.doWork()

        // Verify marked completed
        val processed = repository.findByUUID(model.id)
        processed?.outboxCompletedAt shouldNotBe null
    }
})
```

---

## Database Migrations

**IMPORTANT:** Place migrations in the feature module, not in lemline-runner!

### Location in Feature Module

```
lemline-runner-myfeature/
└── src/main/resources/db/migration/
    ├── postgresql/
    │   └── V{N}__Create_myfeature_table.sql
    ├── mysql/
    │   └── V{N}__Create_myfeature_table.sql
    └── h2/
        └── V{N}__Create_myfeature_table.sql
```

**Naming:** `V{N}__Description.sql` where N is the next available version number across all modules.

**Check existing versions first:**
```bash
# Find highest version number
find . -name "V*.sql" | sort
```

### Migration Template

```sql
-- PostgreSQL version
CREATE TABLE lemline_myfeature
(
    id                   UUID PRIMARY KEY,
    
    -- Instance message (serialized workflow state)
    instance_message     TEXT         NOT NULL,
    
    -- Feature-specific columns
    my_custom_field      VARCHAR(255),
    
    -- Outbox columns (if using outbox pattern)
    outbox_scheduled_for TIMESTAMP    NOT NULL,
    outbox_delayed_until TIMESTAMP    NOT NULL,
    outbox_attempt_count INT          NOT NULL DEFAULT 0,
    outbox_completed_at  TIMESTAMP,
    outbox_failed_at     TIMESTAMP,
    outbox_error_class   VARCHAR(255),
    outbox_error_message VARCHAR(500),
    outbox_error_stacktrace TEXT,
    
    -- Cleanup column (if using cleaner pattern)
    cleanup_after        TIMESTAMP,
    
    -- Timestamps
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Index for outbox queries (FOR UPDATE SKIP LOCKED)
CREATE INDEX idx_lemline_myfeature_pending
    ON lemline_myfeature (outbox_delayed_until)
    WHERE outbox_completed_at IS NULL AND outbox_failed_at IS NULL;

-- Index for cleanup queries
CREATE INDEX idx_lemline_myfeature_cleanup
    ON lemline_myfeature (cleanup_after)
    WHERE cleanup_after IS NOT NULL;
```

### Database-Specific Considerations

**PostgreSQL:**
- Use `UUID` type
- Use `TEXT` for long strings
- Partial indexes with `WHERE` clause

**MySQL:**
- Use `CHAR(36)` instead of UUID
- Use `LONGTEXT` for long strings
- No partial indexes - use regular index

**H2:**
- Use `UUID` type
- Use `CLOB` for very long strings
- Supports partial indexes

### Testing Migrations

```bash
# Test with PostgreSQL
./gradlew :lemline-runner-myfeature:test -Dquarkus.test.profile=postgres

# Test with MySQL
./gradlew :lemline-runner-myfeature:test -Dquarkus.test.profile=mysql

# Test with H2
./gradlew :lemline-runner-myfeature:test -Dquarkus.test.profile=h2
```

---

## Running Tests

```bash
# All tests in main runner
./gradlew :lemline-runner:test

# All tests in feature module
./gradlew :lemline-runner-myfeature:test

# Specific test class
./gradlew :lemline-runner-myfeature:test --tests "com.lemline.runner.myfeature.MyTest"

# Test specific module with specific database
./gradlew :lemline-runner-waits:test -Dquarkus.test.profile=postgres
./gradlew :lemline-runner-waits:test -Dquarkus.test.profile=mysql
./gradlew :lemline-runner-waits:test -Dquarkus.test.profile=h2

# Test all runner modules
./gradlew test -p lemline-runner -p lemline-runner-common -p lemline-runner-waits -p lemline-runner-retries ...
```

---

## Adding a New DSL Feature

**Example: Adding support for a new task type that requires persistence**

1. **Add task to lemline-core** (see core-dev skill)
   - Create model in `lemline-core/src/.../models/tasks/`
   - Create processor in `lemline-core/src/.../processors/`
   - Throw `AsyncTaskException` if needs persistence

2. **Create feature module**
   ```bash
   mkdir -p lemline-runner-myfeature/src/{main,test}/kotlin/com/lemline/runner/myfeature
   mkdir -p lemline-runner-myfeature/src/main/resources/db/migration/{postgresql,mysql,h2}
   ```

3. **Add to settings.gradle.kts**
   ```kotlin
   include("lemline-runner-myfeature")
   ```

4. **Create build.gradle.kts**
   ```kotlin
   dependencies {
       implementation(project(":lemline-common"))
       implementation(project(":lemline-core"))
       implementation(project(":lemline-runner-common"))
   }
   ```

5. **Implement feature** (Model, Repository, Service, Outbox, Cleaner)

6. **Add migrations** for all 3 databases

7. **Register in WorkflowEventHandler**
   ```kotlin
   // In lemline-runner
   when (val state = message.state) {
       is MyFeatureStarted -> myFeatureService.handleStarted(message)
       // ...
   }
   ```

8. **Add tests** for all databases

9. **Add to main runner dependencies**
   ```kotlin
   // In lemline-runner/build.gradle.kts
   implementation(project(":lemline-runner-myfeature"))
   ```

10. **Document in README.md** in the feature module

---

## Related Documentation

- **CLAUDE.md** - Project-wide guidelines and architecture overview
- **AGENTS.md** - Build, test, and lint commands
- **core-dev skill** - lemline-core module (DSL, orchestrators, processors)
- **Serverless Workflow DSL** - https://serverlessworkflow.io/

---

## Module Dependency Graph

```
lemline-common
    ↑
    │
lemline-core
    ↑
    │
lemline-runner-common ←────┐
    ↑                      │
    │                      │
    ├──────────────────────┼─────────────────┐
    │                      │                 │
lemline-runner-*     lemline-runner-*   lemline-runner-*
(feature modules)    (feature modules)   (feature modules)
    ↑                      ↑                 ↑
    │                      │                 │
    └──────────────────────┴─────────────────┘
                           │
                    lemline-runner
                   (main runtime)
```

**Key principle:** Feature modules depend only on common modules, not on each other (except rare cases like schedules/parents).
