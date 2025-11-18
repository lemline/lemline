# Outbox Refactoring Implementation Plan

## Current Status

### ✅ Completed
1. Added missing DatabaseManager imports to all repositories
2. Fixed `WithInstanceRepository.getInstanceMessage()` with reified generics for type safety
3. Made companion object constants public (removed `internal`) for inline function access
4. Updated all repository `createModel()` methods to use type-specific `getInstanceMessage<T>()`
5. Added `transformToCommand()` methods to RetryOutbox and WaitOutbox
6. Commented out broken `ParentOutboxModel.resumeSync()` method (dead code)

### ❌ Current Compilation Errors (4 remaining)
```
1. RetryOutbox.kt:64 - Unresolved reference 'InstanceMessage' (missing import)
2. WaitOutbox.kt:60 - Unresolved reference 'InstanceMessage' (missing import)
3. AbstractOutbox.kt:78 - Type mismatch: sends Event instead of Command
4. ParentOutbox.kt:59 - Type mismatch: sends Event instead of Command (dead code anyway)
```

---

## Phase 1: Fix Remaining Compilation Errors

### Step 1.1: Add Missing Imports
**Files**: `RetryOutbox.kt`, `WaitOutbox.kt`

```kotlin
// Add to both files:
import com.lemline.runner.messaging.instances.InstanceMessage
```

**Expected**: Fixes errors 1 & 2

### Step 1.2: Make AbstractOutbox.process() Not Send Directly
**File**: `AbstractOutbox.kt`

**Current** (line 77-79):
```kotlin
open suspend fun process(entity: T) {
    instanceEmitter.send(entity.instanceMessage)  // ⚠️ Sends Event!
}
```

**Change to**:
```kotlin
/**
 * Process an outbox entity.
 * Subclasses MUST override to transform Event → Command before sending.
 */
abstract suspend fun process(entity: T)
```

**Rationale**: Force each outbox to implement transformation. RetryOutbox and WaitOutbox already override this.

**Expected**: Fixes error 3, reveals error 4 (ParentOutbox doesn't override)

### Step 1.3: Delete ParentOutbox (Dead Code)
**File**: `ParentOutbox.kt`

**Action**: Delete entire file

**Rationale**:
- `outboxConf = null` - no scheduled processing
- `process()` never called
- Actual processing happens in `DatabaseMessageHandler.handleWorkflowCompleted()`

**Expected**: Fixes error 4

**Result**: ✅ All compilation errors fixed

---

## Phase 2: Structural Refactoring (Renames & Inheritance)

### Step 2.1: Rename ParentOutboxModel → ParentWaitingModel

**Files to update**:
1. `models/ParentOutboxModel.kt` → `models/ParentWaitingModel.kt`
   - Rename class
   - Remove `@SerialName("p")` or update if needed
   - Delete commented `resumeSync()` method

2. `repositories/ParentRepository.kt` → Update type references
   ```kotlin
   class ParentRepository : OutboxRepository<ParentWaitingModel>()  // Temporary
   ```

3. `messaging/database/DatabaseMessageHandler.kt`
   - Update `ParentOutboxModel` → `ParentWaitingModel` (2-3 locations)

4. Update any other references (search codebase)

### Step 2.2: Rename ForkModel → ForkWaitingModel

**Files to update**:
1. `models/ForkModel.kt` → `models/ForkWaitingModel.kt`
   - Rename class

2. `repositories/ForkRepository.kt` → Update type references
   ```kotlin
   class ForkRepository : WithInstanceRepository<ForkWaitingModel>()  // Already doesn't extend OutboxRepository!
   ```

3. `messaging/database/DatabaseMessageHandler.kt`
   - Update `ForkModel` → `ForkWaitingModel`

4. Update any other references (search codebase)

### Step 2.3: Change ParentWaitingModel to NOT Extend OutboxModel

**File**: `models/ParentWaitingModel.kt`

**Current**:
```kotlin
data class ParentOutboxModel(...) : OutboxModel() {
    override var outBoxStatus: OutBoxStatus
    override var outboxScheduledFor: Instant?
    override var outboxDelayedUntil: Instant?
    override var outboxAttemptCount: Int
    override var outboxErrorClass: String?
    override var outboxErrorMessage: String?
    override var outboxErrorStackTrace: String?
}
```

**Change to**:
```kotlin
data class ParentWaitingModel(
    override val id: IDV7,
    val instanceMessage: InstanceMessage<WorkflowEvent.RunWorkflowStarted>
) : InstanceModel {
    override val workflowInfo get() = instanceMessage.workflowInfo
    override val workflowState get() = instanceMessage.workflowState
    override val parentId get() = instanceMessage.parentId
}
```

**Database columns to keep**: Just `id` and serialized `instance_message`
**Database columns to remove**: All outbox-specific columns (or mark nullable if keeping for migration)

### Step 2.4: Update ParentRepository to NOT Extend OutboxRepository

**File**: `repositories/ParentRepository.kt`

**Current**:
```kotlin
class ParentRepository : OutboxRepository<ParentWaitingModel>()
```

**Change to**:
```kotlin
class ParentWaitingRepository : WithInstanceRepository<ParentWaitingModel>() {
    override val tableName = "lemline_parents"

    override fun createModel(rs: ResultSet) = ParentWaitingModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.RunWorkflowStarted>()!!
    )
}
```

**Remove**: All outbox-specific column handling

### Step 2.5: Rename ParentRepository → ParentWaitingRepository

**Files to update**:
1. `repositories/ParentRepository.kt` → `repositories/ParentWaitingRepository.kt`
2. Update all references in:
   - `DatabaseMessageHandler.kt`
   - Test files
   - Any DI injections

### Step 2.6: Rename ForkRepository → ForkWaitingRepository

**Files to update**:
1. `repositories/ForkRepository.kt` → `repositories/ForkWaitingRepository.kt`
2. Update all references in:
   - `DatabaseMessageHandler.kt`
   - Test files
   - Any DI injections

---

## Phase 3: Database Migration (If Needed)

### Option A: No Schema Changes (Recommended for MVP)
- Keep existing table structure
- Just don't use outbox columns for ParentWaitingModel
- Set them to NULL on insert

### Option B: Schema Migration
```sql
-- V00X__simplify_parent_table.sql
ALTER TABLE lemline_parents DROP COLUMN outbox_status;
ALTER TABLE lemline_parents DROP COLUMN outbox_scheduled_for;
ALTER TABLE lemline_parents DROP COLUMN outbox_delayed_until;
ALTER TABLE lemline_parents DROP COLUMN outbox_attempt_count;
ALTER TABLE lemline_parents DROP COLUMN outbox_error_class;
ALTER TABLE lemline_parents DROP COLUMN outbox_error_message;
ALTER TABLE lemline_parents DROP COLUMN outbox_error_stacktrace;
```

**Decision**: Defer to later (not needed for functionality)

---

## Phase 4: Testing & Verification

### Step 4.1: Compilation Test
```bash
./gradlew :lemline-runner:compileKotlin
```
**Expected**: 0 errors

### Step 4.2: Unit Tests
```bash
./gradlew :lemline-runner:test
```
**Fix any broken tests** (likely just type/name changes)

### Step 4.3: Integration Tests
Focus on:
- Wait task execution and resumption
- Retry task execution and resumption
- Parent-child workflow execution
- Fork/join task execution

### Step 4.4: Manual Verification
Test workflows with:
1. Wait tasks (lemline_waits outbox)
2. Retry logic (lemline_retries outbox)
3. Synchronous child workflows (lemline_parents storage)
4. Fork/join tasks (lemline_forks storage)

---

## Summary of Changes

### Files to Modify
- ✅ `RetryOutbox.kt` - Add import, add process() override
- ✅ `WaitOutbox.kt` - Add import, add process() override
- ⬜ `AbstractOutbox.kt` - Make process() abstract
- ⬜ `ParentOutbox.kt` - DELETE FILE
- ⬜ `ParentOutboxModel.kt` - Rename to ParentWaitingModel, change inheritance
- ⬜ `ForkModel.kt` - Rename to ForkWaitingModel
- ⬜ `ParentRepository.kt` - Rename to ParentWaitingRepository, change inheritance
- ⬜ `ForkRepository.kt` - Rename to ForkWaitingRepository
- ⬜ `DatabaseMessageHandler.kt` - Update type references
- ⬜ Various test files - Update imports and types

### Architectural Changes
1. **Pattern A (Scheduled Outbox)**: RetryOutbox, WaitOutbox, ScheduleOutbox
   - Keep OutboxModel inheritance
   - Keep OutboxRepository inheritance
   - Transform Event → Command in process()

2. **Pattern B (Event-Driven State)**: ParentWaiting, ForkWaiting
   - Do NOT extend OutboxModel
   - Do NOT extend OutboxRepository (just WithInstanceRepository)
   - No scheduled processing
   - Processed immediately by DatabaseMessageHandler

3. **Pattern C (Terminal)**: FailureModel
   - Standalone model
   - No automatic processing

### Type Safety Achieved
- ✅ Each repository specifies exact WorkflowState type via reified generics
- ✅ Compile-time enforcement of Event → Command transformations
- ✅ No unsafe casts in production code
- ✅ Clear separation: Events = past facts, Commands = future actions

---

## Execution Order

1. **Immediate** (fixes compilation):
   - Add InstanceMessage imports to RetryOutbox, WaitOutbox
   - Make AbstractOutbox.process() abstract
   - Delete ParentOutbox.kt

2. **Next** (structural cleanup):
   - Rename models (ParentOutboxModel, ForkModel)
   - Change ParentWaitingModel to not extend OutboxModel
   - Rename repositories (ParentRepository, ForkRepository)

3. **Finally** (verification):
   - Run tests
   - Manual testing
   - Update documentation

**Estimated Time**: 1-2 hours for all phases
