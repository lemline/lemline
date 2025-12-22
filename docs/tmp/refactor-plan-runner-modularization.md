# Refactor Plan: Runner Module Modularization

## Overview

Split the monolithic `lemline-runner` module into feature-specific submodules to improve separation of concerns, testability, and maintainability.

## Current State Analysis

### Current Structure
```
lemline-runner/
├── src/main/kotlin/com/lemline/runner/
│   ├── cleaner/           # AbstractCleaner + ForkCleaner, ParentCleaner, ListenerCleaner
│   ├── config/            # LemlineConfiguration, DatabaseManager, Flyway
│   ├── messaging/         # WorkflowCommandHandler, WorkflowEventHandler, subscribers
│   ├── models/            # WaitModel, RetryModel, ParentModel, ForkModel, etc.
│   ├── outbox/            # AbstractOutbox + WaitOutbox, RetryOutbox, etc.
│   ├── repositories/      # All repositories + ops/ + helpers/ + with/
│   ├── scheduled/         # AbstractScheduledTask
│   └── ...
└── src/main/resources/db/migration/
    ├── postgresql/V1-V9__*.sql
    ├── mysql/V1-V9__*.sql
    └── h2/V1-V9__*.sql
```

### Current Database Tables
1. `lemline_definitions` (V1) - workflow definitions
2. `lemline_waits` (V2) - wait tasks
3. `lemline_retries` (V3) - retry scheduling
4. `lemline_parents` (V4) - parent-child workflow relationships
5. `lemline_schedules` (V5) - scheduled workflows
6. `lemline_failures` (V6) - permanent failures
7. `lemline_forks` + `lemline_fork_branches` (V7) - parallel fork execution
8. `lemline_listeners` (V8) - CloudEvent listeners
9. `lemline_listener_events` (V9) - listener events

## Proposed New Module Structure

```
lemline/
├── lemline-common/                    # (existing - unchanged)
├── lemline-core/                      # (existing - unchanged)
├── lemline-runner/                    # Main orchestration module
│   └── depends on: all runner-* modules
├── lemline-runner-common/             # Shared infrastructure (NEW)
├── lemline-runner-waits/              # Wait feature (NEW)
├── lemline-runner-retries/            # Retry feature (NEW)
├── lemline-runner-schedules/          # Schedule feature (NEW)
├── lemline-runner-parents/            # Parent-child workflow (NEW)
├── lemline-runner-forks/              # Fork/parallel execution (NEW)
└── lemline-runner-listeners/          # CloudEvent listeners (NEW)
```

## Detailed Module Design

### 1. lemline-runner-common (NEW)

**Purpose**: Shared infrastructure, base classes, and utilities used across all feature modules.

**Contains**:
- `scheduled/AbstractScheduledTask.kt` - base class for scheduled tasks
- `cleaner/AbstractCleaner.kt` - base class for cleanup operations
- `outbox/AbstractOutbox.kt` - base class for outbox pattern
- `repositories/ops/` - CrudRepository, IdRepository, OutboxRepository, etc.
- `repositories/helpers/` - ColumnBindings, IdV7Helper
- `repositories/with/` - WithIdRepository, WithOutboxRepository interfaces
- `models/With*.kt` - WithId, WithOutbox, WithCleanup, WithInstanceMessage interfaces
- `config/DatabaseManager.kt` - database connection management
- `messaging/MessageEmitter.kt` - base emitter class
- `messaging/MessageHandler.kt` - base handler interface
- `messaging/MessageSubscriber.kt` - base subscriber class
- `messaging/InstanceMessage.kt` - message type (depends on lemline-core states)

**Dependencies**:
- `lemline-common`
- `lemline-core` (for states, values)
- Quarkus core, scheduler
- Kotlinx coroutines, serialization

**No database migrations** (provides base classes only)

---

### 2. lemline-runner-waits (NEW)

**Purpose**: Wait task persistence and outbox processing.

**Contains**:
- `models/WaitModel.kt`
- `repositories/WaitRepository.kt`
- `outbox/WaitOutbox.kt`
- `cleaner/WaitCleaner.kt` (NEW - currently missing dedicated cleaner)

**Migrations**: `V2__Create_lemline_waits_table.sql` (for all DB types)

**Dependencies**:
- `lemline-runner-common`

---

### 3. lemline-runner-retries (NEW)

**Purpose**: Retry scheduling and outbox processing.

**Contains**:
- `models/RetryModel.kt`
- `repositories/RetryRepository.kt`
- `outbox/RetryOutbox.kt`
- `cleaner/RetryCleaner.kt` (NEW - currently missing dedicated cleaner)

**Migrations**: `V3__Create_lemline_retries_table.sql`

**Dependencies**:
- `lemline-runner-common`

---

### 4. lemline-runner-schedules (NEW)

**Purpose**: Workflow scheduling (cron, after completion).

**Contains**:
- `models/ScheduleModel.kt`
- `repositories/ScheduleRepository.kt`
- `outbox/ScheduleOutbox.kt`
- `cleaner/ScheduleCleaner.kt` (NEW - currently missing dedicated cleaner)

**Migrations**: `V5__Create_lemline_schedules_table.sql`

**Dependencies**:
- `lemline-runner-common`

---

### 5. lemline-runner-parents (NEW)

**Purpose**: Parent-child workflow relationships (RunWorkflow sync).

**Contains**:
- `models/ParentModel.kt`
- `repositories/ParentRepository.kt`
- `cleaner/ParentCleaner.kt`

**Migrations**: `V4__Create_lemline_parents_table.sql`

**Dependencies**:
- `lemline-runner-common`

**Note**: No outbox - parent completion is triggered by child completion events.

---

### 6. lemline-runner-forks (NEW)

**Purpose**: Parallel fork execution tracking.

**Contains**:
- `models/ForkModel.kt`
- `models/ForkBranchModel.kt`
- `repositories/ForkRepository.kt`
- `repositories/ForkBranchRepository.kt`
- `cleaner/ForkCleaner.kt`

**Migrations**: `V7__Create_lemline_forks_tables.sql`

**Dependencies**:
- `lemline-runner-common`

**Note**: No outbox - branch completion is tracked via events.

---

### 7. lemline-runner-listeners (NEW)

**Purpose**: CloudEvent listener management.

**Contains**:
- `models/ListenerModel.kt`
- `models/ListenerEventModel.kt`
- `models/ListenerStrategy.kt`
- `repositories/ListenerRepository.kt`
- `repositories/ListenerEventRepository.kt`
- `outbox/ListenerCompletionOutbox.kt`
- `outbox/ListenerForeachOutbox.kt`
- `outbox/ListenerTimeoutOutbox.kt`
- `cleaner/ListenerCleaner.kt`

**Migrations**: `V8__Create_lemline_listeners_tables.sql`, `V9__Create_lemline_listener_events_table.sql`

**Dependencies**:
- `lemline-runner-common`

---

### 8. lemline-runner (REFACTORED)

**Purpose**: Main orchestration - configuration, message handling, CLI.

**Retains**:
- `config/LemlineConfiguration.kt` - main configuration
- `config/LemlineConfigSource.kt` - config transformation
- `config/FlywayMigration.kt` - coordinates migrations from all modules
- `messaging/commands/WorkflowCommandHandler.kt` - command processing
- `messaging/commands/WorkflowCommandEmitter.kt` - command emission
- `messaging/commands/WorkflowCommandSubscriber.kt` - command subscription
- `messaging/events/WorkflowEventHandler.kt` - event routing to feature modules
- `messaging/events/WorkflowEventEmitter.kt` - event emission
- `messaging/events/WorkflowEventSubscriber.kt` - event subscription
- `messaging/cloudevents/` - CloudEvent handling
- `messaging/lifecycle/` - Lifecycle event handling
- `cli/` - all CLI commands
- `definitions/` - definition service
- `starters/` - workflow starter
- `models/DefinitionModel.kt`
- `models/FailureModel.kt`
- `models/InstanceModel.kt`
- `repositories/DefinitionRepository.kt`
- `repositories/FailureRepository.kt`
- `activities/` - activity execution

**Migrations**:
- `V1__Create_lemline_definitions_table.sql`
- `V6__Create_lemline_failures_table.sql`

**Dependencies**:
- `lemline-runner-common`
- `lemline-runner-waits`
- `lemline-runner-retries`
- `lemline-runner-schedules`
- `lemline-runner-parents`
- `lemline-runner-forks`
- `lemline-runner-listeners`

---

## Migration Strategy

### Phase 1: Create lemline-runner-common
1. Create module with build.gradle.kts
2. Move base classes (Abstract*, base repositories)
3. Move shared models (With* interfaces)
4. Update lemline-runner to depend on it
5. Verify tests pass

### Phase 2: Create feature modules (one at a time)
For each feature module:
1. Create module structure
2. Move models, repositories, outbox, cleaner
3. Move migrations to feature module
4. Update lemline-runner to depend on feature module
5. Update imports in WorkflowEventHandler
6. Verify tests pass

**Order** (based on dependencies and complexity):
1. lemline-runner-waits (simplest, no cross-dependencies)
2. lemline-runner-retries (similar to waits)
3. lemline-runner-schedules (standalone)
4. lemline-runner-parents (standalone)
5. lemline-runner-forks (has fork + branch models)
6. lemline-runner-listeners (most complex, multiple tables)

### Phase 3: Clean up lemline-runner
1. Remove moved code
2. Update WorkflowEventHandler to use injected services from feature modules
3. Consolidate migrations coordination

---

## Flyway Migration Handling

### Challenge
Flyway expects migrations in a single location. With modules split, we need a strategy.

### Options

**Option A: Centralized Migrations (Recommended)**
- Keep all migrations in `lemline-runner`
- Feature modules don't contain migrations
- Pro: Simple Flyway config
- Con: Migrations not co-located with feature code

**Option B: Migration Aggregation**
- Each feature module has its migrations
- `lemline-runner` aggregates them at runtime via classpath
- Pro: Migrations co-located with features
- Con: More complex Flyway setup, potential ordering issues

**Option C: Flyway Locations Array**
- Configure Flyway with multiple locations
- `quarkus.flyway.locations=classpath:db/migration/{db},classpath:lemline-runner-waits/db/migration/{db},...`
- Pro: Clean separation
- Con: Configuration complexity

### Recommendation
Start with **Option A** (centralized migrations) for simplicity. The logical grouping by V* numbers already indicates feature ownership. Migration co-location can be added later if needed.

---

## Configuration Updates

### LemlineConfiguration
Update `OutboxConfig` to support feature-module-based configuration:

```kotlin
interface OutboxConfig {
    fun enabled(): Optional<Boolean>
    fun waits(): ProcessOutboxConfig      // renamed from wait()
    fun retries(): ProcessOutboxConfig    // renamed from retry()
    fun schedules(): ProcessOutboxConfig  // renamed from schedule()
    fun listeners(): Optional<ProcessOutboxConfig>
    fun parents(): CleanupOutboxConfig
    fun forks(): CleanupOutboxConfig
}
```

---

## Dependency Graph

```
lemline-common
      ↓
lemline-core
      ↓
lemline-runner-common
      ↓
┌─────┴─────┬──────────┬──────────┬─────────┬──────────┐
↓           ↓          ↓          ↓         ↓          ↓
waits    retries   schedules   parents   forks    listeners
└─────┬─────┴──────────┴──────────┴─────────┴──────────┘
      ↓
lemline-runner
```

---

## Breaking Changes

### None Expected
- All changes are internal refactoring
- Public CLI interface unchanged
- Configuration structure unchanged
- Message format unchanged
- Database schema unchanged

---

## Testing Strategy

1. **Unit Tests**: Move with their corresponding code
2. **Integration Tests**: Remain in `lemline-runner` (they test the full stack)
3. **Verify**: Run `./gradlew test` after each phase

---

## Estimated Impact

- **New Modules**: 7 (1 common + 6 feature)
- **Files Moved**: ~40-50
- **New Files**: ~14 (build.gradle.kts for each module + cleaners)
- **Modified Files**: ~10-15 (imports, dependencies)

---

## Decisions Made

1. **Migration Location**: Centralized in lemline-runner (Option A)
2. **Cleaner Classes**: Yes, add missing cleaners (WaitCleaner, RetryCleaner, ScheduleCleaner)
3. **Test Fixtures**: Keep in lemline-runner (centralized)

---

## Next Steps

1. **Approve this plan** to begin implementation
2. Execute Phase 1 (lemline-runner-common)
3. Execute Phase 2 (feature modules one by one)
4. Execute Phase 3 (cleanup)
