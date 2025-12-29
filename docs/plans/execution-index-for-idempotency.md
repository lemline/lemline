# Execution Index for Idempotent IDs

## Status

Proposed

## Problem Statement

### The Bug

The `ListenForeachSequentialDebugTest` test fails when a `wait` step is added inside a `listen` task's `foreach` block. The second event's wait is never persisted to the database.

### Root Cause

The current idempotency mechanism uses `workflowId + position + workflowStep` to derive unique IDs for database records (waits, retries, parents, etc.). This approach is **fragile** because:

1. **Global step counter doesn't track per-node executions**: The `workflowStep` in `RootState` is a single global counter that increments on each task entry. However, when workflow state is restored from the database (as in listen+foreach), multiple executions can start from the same saved state with the same step counter.

2. **Listen+foreach specific issue**: When processing events sequentially:
   - Event 1: Starts with `nodeStack` (step=N) → increments to N+1 → wait ID = `hash(position, N+1)`
   - Event 2: Starts with **same** `nodeStack` (step=N) → increments to N+1 → wait ID = `hash(position, N+1)` (collision!)
   
   Both events derive the **same wait ID** because `ListenerForeachOutbox` always uses the original `listener.instanceMessage.nodeStack`.

3. **Broader brittleness**: The same issue could manifest in:
   - Nested loops (for inside for)
   - Fork inside foreach
   - Retry attempts if state is restored incorrectly
   - Any scenario where the same position is executed multiple times from restored state

### Evidence

From `WaitRepository.kt`:
```kotlin
// Uses ON CONFLICT DO NOTHING - silently ignores duplicates
INSERT INTO lemline_waits (...) VALUES (...) ON CONFLICT DO NOTHING
```

From `WaitService.kt`:
```kotlin
val waitId = instance.workflowState.nodeStack.deriveIdempotentId("-wait")
// This derives from: workflowId + position + workflowStep + suffix
```

The second event's wait INSERT returns 0 rows affected because the ID already exists.

## Chosen Solution

### Overview

Add an **execution index** to each node in the `NodeStack`. Use these indices to derive a unique **execution key** (e.g., `"0-0-2-0-0"`) that, combined with the position, guarantees unique idempotent IDs.

### Key Insight

We need to distinguish between three concepts:

| Concept | Description | Example | Storage |
|---------|-------------|---------|---------|
| **Static Position** | Location in workflow definition tree | `/do/forLoop/do/wait` | `Node.position` (immutable) |
| **Execution Index** | Which execution of this node (0-indexed) | `2` (third iteration) | `NodeState.executionIndex` |
| **Execution Key** | Concatenation of all indices in stack | `"0-0-2-0-0"` | Derived from NodeStack |

The **execution key** captures the full execution context without modifying the position format.

### Design

#### 1. Add `executionIndex` to NodeState base class

```kotlin
@Serializable
sealed class NodeState {
    abstract val startedAt: Instant
    
    /**
     * Execution index for this node frame. Starts at 0, increments on:
     * - Loop iterations (for, foreach)
     * - Retry attempts  
     * - Re-entry via goto/flow directives
     */
    open val executionIndex: Int = 0
    
    open val scope: Scope = JsonObject(mapOf())
}
```

Each `NodeState` subclass includes `executionIndex` with default value `0`.

#### 2. Derive execution key from NodeStack

```kotlin
// NodeStack.kt
fun deriveExecutionKey(): String =
    frames.joinToString("-") { (_, state) -> 
        state.executionIndex.toString() 
    }

// Example: "0-0-2-0-0" for a wait inside the 3rd iteration of a for loop
```

#### 3. Update idempotent ID derivation

```kotlin
// NodeStack.kt
fun deriveIdempotentId(suffix: String = ""): IDV7 =
    IDV7.deriveFromComponents(
        baseId = rootState.workflowId.value,
        position = currentPosition.toString(),
        executionKey = deriveExecutionKey(),
        suffix = suffix
    )
```

#### 4. Increment index at iteration/retry points

| Scenario | Where | When |
|----------|-------|------|
| For loop | `ForProcessor` | Before each iteration |
| Listen foreach | `ListenerForeachOutbox` | For each event (use event's sort_key) |
| Retry | `TryProcessor` / `RetryOutbox` | Before each retry attempt |
| Goto re-entry | `DoProcessor` | When jumping backward |

### Example: Listen+Foreach Fixed

```yaml
do:
  - collectReadings:
      listen:
        to:
          any:
            with:
              type: sensor.reading
        until:
          expression: ". | length >= 3"
        foreach:
          do:
            - wait: PT0.4S
```

**Before (broken):**
```
Event 1: executionKey = "0-0-0-0" → waitId = hash(..., "0-0-0-0", "-wait")
Event 2: executionKey = "0-0-0-0" → waitId = hash(..., "0-0-0-0", "-wait") ← COLLISION!
```

**After (fixed):**
```
Event 1: ListenState(executionIndex=0) → executionKey = "0-0-0-0" → unique waitId
Event 2: ListenState(executionIndex=1) → executionKey = "0-0-1-0" → unique waitId
Event 3: ListenState(executionIndex=2) → executionKey = "0-0-2-0" → unique waitId
```

## Why This Solution

### Alternatives Considered

#### Alternative 1: Update listener's nodeStack after each foreach iteration

**Approach**: After each foreach iteration completes, update `listener.instanceMessage.nodeStack` with the incremented `workflowStep`.

**Rejected because**:
- Fixes the symptom, not the root cause
- Global step counter is still fragile for other scenarios (nested loops, fork+foreach)
- Requires database update after each iteration (performance impact)

#### Alternative 2: Include eventId in wait ID derivation

**Approach**: For listen contexts, include `ListenState.eventId` in the wait ID: `hash(position, step, eventId, suffix)`.

**Rejected because**:
- Special-case fix only for listen+foreach
- Doesn't address the broader brittleness
- eventId is a string (CloudEvent ID), not a controlled sequence

#### Alternative 3: Encode index in position string

**Approach**: Change position format from `/do/forLoop/do/wait` to `/do:0/forLoop:2/do:0/wait:0`.

**Rejected because**:
- Breaking change to position format (209 usages across 31 files)
- Duplicates information already in NodeState (ForState has `index`)
- Complicates position parsing and comparison
- Database migration complexity

#### Alternative 4: Separate execution path array

**Approach**: Keep position as-is, add parallel `executionPath: List<Int>` field.

**Rejected because**:
- Two parallel structures to keep in sync
- More complex serialization
- Essentially what we're doing, but less elegantly

### Why Execution Index in NodeState

1. **Natural fit**: Each node's state already tracks runtime information. The execution index is runtime metadata about "which execution of this node."

2. **No duplication**: Position stays static (definition), index is in state (execution). They're conceptually different things.

3. **Survives serialization**: The index is part of the serialized `NodeStack`, so it's preserved across database save/restore cycles.

4. **Backward compatible**: Adding a field with default value (`executionIndex: Int = 0`) is backward compatible with existing serialized states.

5. **Minimal changes**: ~10 files to modify, no database schema changes, no position format changes.

6. **Addresses root cause**: Any node that can execute multiple times (loops, retries, foreach) gets unique identification through its execution index.

### Comparison with Industry Patterns

| System | Approach | Our Equivalent |
|--------|----------|----------------|
| **Temporal** | Global sequence counter (`seq`) per workflow | `workflowStep` (current, fragile) |
| **Temporal** | Activity ID = `${seq}` | Our new `executionKey` |
| **AWS Step Functions** | State name + Map index | Position + `executionIndex` |
| **Event sourcing** | Event sequence number | `executionIndex` per node |

Our solution is similar to Temporal's approach but **scoped per-node** rather than global, making it more robust for complex nesting scenarios.

## Implementation Plan

### Phase 1: Core Changes

1. **Add `executionIndex` to `NodeState`** base class
2. **Update all `NodeState` subclasses** to include `executionIndex` parameter
3. **Add `deriveExecutionKey()`** method to `NodeStack`
4. **Update `deriveIdempotentId()`** to use execution key instead of `workflowStep`

### Phase 2: Increment Logic

5. **Update `ForProcessor`** to increment index on each iteration
6. **Update `ListenerForeachOutbox`** to set index for each event
7. **Update retry logic** to increment index on retry attempts
8. **Handle goto re-entry** in `DoProcessor` if applicable

### Phase 3: Testing & Cleanup

9. **Fix `ListenForeachSequentialDebugTest`** - should pass with new logic
10. **Add tests** for nested loops, fork+foreach, retries
11. **Deprecate `workflowStep`** usage for ID derivation (keep for backward compat)

### Files to Modify

| File | Change |
|------|--------|
| `NodeState.kt` | Add `executionIndex` abstract property |
| `DoState.kt` | Add `executionIndex` parameter |
| `ForState.kt` | Add `executionIndex` parameter |
| `ListenState.kt` | Add `executionIndex` parameter |
| `TryState.kt` | Add `executionIndex` parameter |
| `TaskState.kt` | Add `executionIndex` parameter |
| `RootState.kt` | Add `executionIndex` parameter |
| `NodeStack.kt` | Add `deriveExecutionKey()`, update `deriveIdempotentId()` |
| `ForProcessor.kt` | Increment index on iteration |
| `ListenerForeachOutbox.kt` | Set index for each event |
| `IDV7.kt` | Update `deriveFromPositionAndStep` or add new method |

### Estimated Effort

- Core changes (Phase 1): 1-2 days
- Increment logic (Phase 2): 2-3 days
- Testing & cleanup (Phase 3): 1-2 days
- **Total**: ~1 week

## Migration

### Serialization Compatibility

Adding `executionIndex: Int = 0` to data classes is **backward compatible**:
- Existing serialized states without `executionIndex` will deserialize with default value `0`
- New states will include `executionIndex` in serialization

### Database

No schema changes required:
- `NodeStack` is serialized as JSON in `workflow_state` column
- IDs will be derived differently but that's transparent to the schema

### In-Flight Workflows

Workflows in progress during deployment:
- Will continue with `executionIndex = 0` for all nodes (default)
- This is correct for non-iterating scenarios
- For iterations in progress, first iteration might have index 0 (safe, just means IDs derived differently)

## Future Considerations

### Deprecating workflowStep

Once execution index is proven stable:
1. Stop using `workflowStep` in ID derivation
2. Keep `workflowStep` in `RootState` for backward compatibility
3. Eventually remove in a future major version

### Additional Use Cases

The execution index could also be useful for:
- **Observability**: Knowing "this is the 5th retry" or "3rd loop iteration"
- **Debugging**: Clearer execution traces
- **Metrics**: Tracking iteration counts, retry rates

## References

- [ADR-0009: Dynamic Step Index](../adr/0009-dynamic-step-index.md) - Current (fragile) approach
- [Temporal Activity ID generation](https://docs.temporal.io/activities#activity-id) - Industry reference
- Original bug: `ListenForeachSequentialDebugTest` failure with wait step
