# Spec Command

Create a detailed technical specification for a new feature in the Lemline workflow orchestration runtime.

**Feature request:** {{placeholderText}}

## Process

1. **Review documentation** to understand project standards:
    - `/CLAUDE.md` - Project overview and architecture
    - `/lemline-core/docs/` - Developer guides for core logic
    - `/lemline-runner/docs/` - Developer guides for infrastructure
    - `/docs/adr/` - Architecture Decision Records

2. **Read the codebase** to understand existing patterns:
    - Explore similar task types, instances, repositories
    - Check existing nodes in `lemline-core/src/main/kotlin/com/lemline/core/nodes/`
    - Review outbox patterns in `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/`
    - Check existing migrations in `db/migration/`

3. **Create specification with sensible defaults:**
    - **State Management**: Use exception-driven control flow (WaitStartedException, TaskRetriedException, etc.)
    - **Data model**: UUID v7 primary keys (IDV7), created_at/updated_at timestamps, snake_case DB names
    - **Repositories**: Use Kotlin coroutines (`suspend` functions), implement `findByUUID(uuid: IDV7): T?`
    - **Outbox pattern**: PENDING → processing → SENT, use FOR UPDATE SKIP LOCKED
    - **Messaging**: Distinguish commands-in/out (high-throughput) vs events-out (durable operations)
    - **Testing**: Use Kotest with `@QuarkusTest`, test with PostgreSQL/MySQL/H2

4. **Write specification to `/docs/features/spec_[feature_name].md`** containing:
   ```markdown
   # Feature: [Name]

   ## Overview
   Brief description and purpose in the workflow execution context

   ## Workflow Integration

   ### Task Type (if adding new task)
   - **Task Name**: `taskName`
   - **Model Location**: `lemline-core/src/main/kotlin/com/lemline/core/models/tasks/`
   - **Instance Location**: `lemline-core/src/main/kotlin/com/lemline/core/instances/`
   - **Control Flow**: Describe exception-based control (e.g., throws WaitStartedException)

   ### State Management
   - **NodeState**: What state needs to persist between steps
   - **NodePosition**: How position navigates through the task

   ## Data Model

   ### Entity: [EntityName]
   ```kotlin
   data class EntityModel(
       val id: IDV7 = IDV7.generate(),
       val workflowId: IDV7,
       // ... fields ...
       val status: EntityStatus = EntityStatus.PENDING,
       val createdAt: Instant = Instant.now(),
       val updatedAt: Instant = Instant.now()
   )
   ```

   ### Repository
   ```kotlin
   interface EntityRepository : WithUUIDRepository<EntityModel> {
       suspend fun findByUUID(uuid: IDV7): EntityModel?
       suspend fun findByWorkflowId(workflowId: IDV7): List<EntityModel>
       suspend fun insertBatch(entities: List<EntityModel>)
   }
   ```

   ## Database Migration

   **V[N]__[description].sql** (for each supported database)
   ```sql
   CREATE TABLE lemline_[entity] (
       id UUID PRIMARY KEY,
       workflow_id UUID NOT NULL,
       -- ... fields ...
       status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
       updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
   );
   CREATE INDEX idx_lemline_[entity]_workflow ON lemline_[entity](workflow_id);
   CREATE INDEX idx_lemline_[entity]_status ON lemline_[entity](status);
   ```

   ## Implementation Files

   ### lemline-core (workflow logic)
    - `lemline-core/src/main/kotlin/com/lemline/core/models/tasks/[Task].kt`
    - `lemline-core/src/main/kotlin/com/lemline/core/instances/[Task]Instance.kt`

   ### lemline-runner (infrastructure)
    - `lemline-runner/src/main/kotlin/com/lemline/runner/models/[Entity]Model.kt`
    - `lemline-runner/src/main/kotlin/com/lemline/runner/repositories/[Entity]Repository.kt`
    - `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/[Entity]Outbox.kt` (if outbox needed)
    - `lemline-runner/src/main/resources/db/migration/postgresql/V[N]__[description].sql`
    - `lemline-runner/src/main/resources/db/migration/mysql/V[N]__[description].sql`
    - `lemline-runner/src/main/resources/db/migration/h2/V[N]__[description].sql`

   ### Tests
    - `lemline-core/src/test/kotlin/com/lemline/core/tests/[Feature]Test.kt`
    - `lemline-runner/src/test/kotlin/com/lemline/runner/tests/[Feature]Test.kt`

   ## Messaging Flow

   Describe how the feature integrates with messaging channels:
    - **commands-in**: [what messages trigger this feature]
    - **commands-out**: [what messages this feature emits]
    - **events-out**: [what database operations are needed]

   ## Key Implementation Details
    - Exception-driven control flow specifics
    - State serialization requirements
    - Retry/backoff policies if applicable
    - Parent-child workflow interactions if applicable

   ## Testing Checklist
    - [ ] Unit tests for task logic (lemline-core)
    - [ ] Integration tests with database (lemline-runner)
    - [ ] Test with PostgreSQL, MySQL, and H2
    - [ ] Test retry/error scenarios
    - [ ] Test state persistence across restarts

   ## Open Questions
    - [Only if genuinely ambiguous]
   ```

5. **Present the specification** to the user with:
    - Summary of the proposed solution
    - Key decisions made (and why)
    - Any questions (only if truly needed)
    - Ask: "Should I proceed with implementation?"

## Best Practice Defaults

**When in doubt:**

- Use exception-driven control flow for async operations
- Add to outbox tables for durable operations (waits, retries, parent tracking)
- Include standard timestamps (created_at, updated_at)
- Use IDV7 (time-sortable UUIDs) for all primary keys
- Add indexes for foreign keys and frequently queried fields (workflow_id, status)
- Use `suspend` functions for all database operations
- Use batch operations for performance (`insertBatch`, `updateBatch`)
- Test with all supported databases (PostgreSQL, MySQL, H2)
- Follow existing naming conventions in the codebase

**Only ask questions when:**

- Business logic is fundamentally ambiguous
- Multiple architectural approaches have significant tradeoffs
- The request conflicts with existing patterns
- The feature significantly changes the execution model
