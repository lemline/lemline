# [ADR-0008] Fork Task Error Management

## Status

Accepted

## Context

Fork tasks in Lemline enable parallel execution of branches, with two execution modes controlled by the `compete` property:

- **compete=true** (racing): Branches race against each other, first to complete wins
- **compete=false** (cooperative, default): All branches execute, all outputs collected

Prior to this ADR, fork error handling was incomplete:

1. Branch failures were tracked at the database level (`ForkBranchModel.failedAt`, `failureId`) but not handled in the workflow execution logic
2. The fork completion logic only checked for successful branch completion, ignoring failures
3. There was no defined behavior for what happens when branches fail in either compete mode
4. Fork tasks did not participate in the try/catch error propagation system

This created ambiguity: Should a fork fail if one branch fails? Should it wait for all branches? How should forks interact with parent try/catch blocks?

## Decision

We establish **fork tasks as error boundaries** with distinct error semantics based on the `compete` mode:

### Fork as Error Boundary

When the WorkflowOrchestrator navigates up the node tree looking for try/catch handlers (in `processInternalWorkflowException`), it **stops at fork task boundaries**. Fork tasks handle errors internally according to their compete strategy before deciding whether to propagate errors to parent try/catch blocks.

**Rationale**: Forks represent a distinct execution context with multiple parallel branches. The error handling strategy depends on the fork's compete mode, making it semantically different from simple task composition. Treating forks as boundaries provides clear, predictable behavior.

### Compete Mode: Racing Branches (`compete=true`)

**Error Semantics**: Ignore branch failures, wait for first success. Only fail if **ALL** branches fail.

**Behavior**:
- When a branch fails: Track the error in `ForkState.failedBranches`, continue waiting for other branches
- When first branch succeeds: Fork completes successfully with that branch's output, ignore any previous failures
- When all branches fail: Fork fails and propagates the **last error** up to parent try/catch (or fails workflow if none)

**Rationale**: In racing mode, branches are alternative strategies to achieve the same goal. If one approach fails, we still have other options. The fork should only fail when all alternatives have been exhausted.

### Cooperative Mode: All Must Succeed (`compete=false`)

**Error Semantics**: Any branch failure **immediately** fails the fork.

**Behavior**:
- When any branch fails: Fork immediately fails and propagates that error up to parent try/catch (or fails workflow if none)
- No waiting for other branches to complete
- Other running branches continue in background but their results are ignored

**Rationale**: In cooperative mode, all branches must succeed to produce the complete result (array of all outputs). If any branch fails, the complete result cannot be assembled, so the fork should fail immediately rather than wasting resources waiting for other branches.

### Error Propagation

When a fork fails (per the above rules):
1. The error is thrown as an `InternalException` from the fork's position
2. The error can be caught by parent try/catch blocks following normal error propagation rules
3. If no try/catch handler is found, the workflow fails with that error

### Error Tracking Strategy

**Error tracking is separated by execution mode** to avoid bloating serialized workflow state:

- **CONTINUOUS mode (ForkSync)**: Tracks failures locally in memory during execution
  - Uses local `failedBranches` map within `executeCompete()` method
  - Discarded after fork completes/fails - no state persistence needed

- **STEP-BY-STEP mode (database)**: Tracks failures in `ForkBranchModel` table
  - Uses existing `failedAt` and `failureId` columns
  - Database queries determine fork completion status based on branch states
  - Enables stateless workers - any worker can resume fork coordination

**ForkState remains minimal**:
```kotlin
data class ForkState(
    override val startedAt: Instant,
    val lastCompleted: String?
    // NO failedBranches field - tracked externally per execution mode
)
```

This design:
- Keeps serialized state small (flows through messages efficiently)
- Maintains clean separation between sync and async execution
- Follows Lemline's "minimize database usage" philosophy
- Enables horizontal scaling without state bloat

## Consequences

### Positive

- **Predictable Behavior**: Clear, documented error semantics for both compete modes
- **Proper Try/Catch Integration**: Forks now participate correctly in the error handling system
- **Resource Efficiency**: Cooperative mode fails fast instead of waiting for doomed branches
- **Resilience**: Compete mode leverages alternative strategies, improving fault tolerance
- **Minimal State Overhead**: ForkState remains small - errors tracked externally per execution mode
- **Scalability**: Stateless workers can handle fork coordination via database state
- **Consistency**: Error boundary concept aligns with fork's role as a distinct execution context

### Negative

- **Breaking Change**: Workflows relying on undefined fork error behavior may change behavior
- **Complexity**: Adds conditional logic to fork completion checks (compete vs. cooperative)
- **Background Work**: Cooperative mode continues executing other branches after failure (could add cancellation in future)
- **Database Load**: Compete mode requires querying all branch states to determine completion

### Neutral

- **Documentation Requirement**: Teams must understand compete mode affects error handling, not just output
- **Testing Requirement**: Both compete modes need comprehensive error scenario testing

## Alternatives Considered

### Alternative 1: Always Fail on First Branch Failure

Regardless of compete mode, fail the fork as soon as any branch fails.

**Rejected because**:
- Defeats the purpose of compete mode as a fault-tolerance mechanism
- Doesn't leverage alternative strategies when one approach fails
- Users would lose the ability to implement "try multiple approaches" patterns

### Alternative 2: Always Wait for All Branches

Regardless of compete mode, wait for all branches to complete before determining success/failure.

**Rejected because**:
- Wastes resources in cooperative mode when failure is already certain
- Delays failure detection and recovery
- Increases latency for workflows that should fail fast

### Alternative 3: Fork Not as Error Boundary

Let error navigation continue through fork nodes to parent try/catch blocks.

**Rejected because**:
- Creates ambiguity about whether individual branch errors or fork-level errors should be caught
- Doesn't allow fork to apply its compete strategy to error handling
- Makes it impossible to implement "retry with alternatives" patterns (compete mode + try/catch)

### Alternative 4: Configurable Error Strategies

Add explicit `errorStrategy` property to fork tasks (e.g., `failFast`, `waitAll`, `firstSuccess`).

**Rejected because**:
- Adds complexity to the DSL
- The `compete` property already captures user intent for both output and error handling
- Would create combinations that don't make semantic sense (e.g., compete=true with failFast)

## Implementation Approach

### Important Design Note: Error Tracking Storage

**Branch failure tracking is NOT stored in ForkState** to avoid bloating serialized workflow state that flows through messages.

Instead:
- **CONTINUOUS mode**: ForkSync.kt tracks failures **locally** in memory during execution (using local variables)
- **STEP-BY-STEP mode**: ForkBranchModel in database tracks failures via existing `failedAt` and `failureId` fields

This separation maintains clean boundaries between execution modes and avoids unnecessary state serialization.

### Phase 1: Core Logic (lemline-core)

1. **Update ForkState** (`lemline-core/src/main/kotlin/com/lemline/core/states/ForkState.kt`):
   - Add documentation clarifying that error tracking is NOT stored here
   - No schema changes needed (keeps state minimal)

2. **Add ForkBranchFailed Event** (`lemline-core/src/main/kotlin/com/lemline/core/states/WorkflowState.kt`):
   - Create new `ForkBranchFailed` event type (parallel to `ForkBranchCompleted`)
   - Carries branch name, error, and failedAt timestamp

3. **Update ForkSync** (`lemline-core/src/main/kotlin/com/lemline/core/orchestrator/sync/ForkSync.kt`):
   - Track failures **locally** using `val failedBranches = mutableMapOf<String, InternalException.Error>()`
   - Implement compete-mode error logic: collect failures, only fail when all branches fail
   - Implement cooperative-mode error logic: fail immediately on first branch failure
   - Propagate errors by throwing InternalException with appropriate error

4. **Update WorkflowOrchestrator** (`lemline-core/src/main/kotlin/com/lemline/core/orchestrator/WorkflowOrchestrator.kt`):
   - Modify `processInternalWorkflowException` to detect fork boundaries via `isForkBoundary()`
   - Stop error navigation when reaching a fork node (fork handles error per compete strategy)
   - Add `getFailedForkBranch()` to detect when errors occur within fork branches
   - Emit `ForkBranchFailed` events when branch execution fails in async mode

### Phase 2: Runner Infrastructure (lemline-runner)

5. **Update WorkflowCommandHandler** (`lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`):
   - Add case for `ForkBranchFailed` events to route to database channel

6. **Update WorkflowEventHandler** (`lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`):
   - Add `handleForkBranchFailed()` method to process branch failures
   - Update `ForkBranchModel` with `failedAt` and `failureId` (using existing fields)
   - Create `FailureModel` record for the error
   - Implement compete-mode logic: wait for all branches to finish, check if any succeeded
     - If any succeeded: complete fork with that output
     - If all failed: fail fork with last error, resume parent with error
   - Implement cooperative-mode logic: fail fork immediately on first branch failure
   - Generate `InstanceMessage` to resume parent workflow (with or without error)

### Phase 3: Testing

5. **Add error tests** (`lemline-core/src/test/kotlin/com/lemline/core/orchestrator/bases/ForkTaskExecutionTest.kt`):
   - Test compete=true with mix of successes and failures (first success wins)
   - Test compete=true with all branches failing (last error propagated)
   - Test compete=false with first branch failing (immediate fork failure)
   - Test compete=false with later branch failing (immediate fork failure)
   - Test fork errors caught by parent try/catch
   - Test fork errors causing workflow failure when no handler

6. **Add integration tests** (`lemline-runner/src/test/kotlin/...`):
   - Test distributed fork error handling with database
   - Test concurrent branch failures
   - Test error propagation to parent workflows

## References

- [ADR-0002: Workflow Execution Model](0002-workflow-execution-model.md) - Node-based execution and state management
- [ADR-0003: Messaging Architecture](0003-messaging-architecture.md) - Event-driven message flow
- [ADR-0005: Error Handling Approach](0005-error-handling-approach.md) - General error handling strategy
- [Serverless Workflow Specification - Fork Task](https://github.com/serverlessworkflow/specification/blob/main/specification.md#fork-task) - DSL specification
- `lemline-core/src/main/kotlin/com/lemline/core/processors/ForkProcessor.kt` - Current implementation
- `lemline-core/src/main/kotlin/com/lemline/core/orchestrator/sync/ForkSync.kt` - Fork synchronization logic
