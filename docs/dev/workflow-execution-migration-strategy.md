# Workflow Execution Migration Strategy

**Version**: 1.0
**Date**: 2025-01-08
**Status**: Draft

This document outlines the recommended approach for integrating the new functional workflow execution model into the existing Lemline codebase while maintaining backward compatibility and minimizing risk.

## Table of Contents

1. [Current Architecture Analysis](#current-architecture-analysis)
2. [Migration Approach Options](#migration-approach-options)
3. [Recommended Approach](#recommended-approach)
4. [Directory Structure](#directory-structure)
5. [Shared Components Strategy](#shared-components-strategy)
6. [Feature Toggle Implementation](#feature-toggle-implementation)
7. [Gradual Migration Path](#gradual-migration-path)
8. [Testing Strategy](#testing-strategy)
9. [Rollback Plan](#rollback-plan)

---

## Current Architecture Analysis

### Existing Module Structure

```
lemline/
├── lemline-common/          # Shared utilities, values, logger
├── lemline-core/            # Workflow execution engine
│   └── src/main/kotlin/com/lemline/core/
│       ├── activities/      # Activity runners (HTTP, Script, etc.)
│       ├── definitions/     # Workflow definition cache
│       ├── errors/          # Exception types
│       ├── expressions/     # JQ expression evaluator
│       ├── instances/       # NodeInstance implementations (current)
│       ├── nodes/           # Node definitions and state
│       ├── processor/       # Processor.kt (main execution engine)
│       ├── schemas/         # JSON schema validation
│       ├── utils/           # Utilities
│       └── workflows/       # WorkflowState, NodeStates
└── lemline-runner/          # Quarkus runtime (messaging, DB, CLI)
```

### Current Execution Flow

```
InstanceMessage → InstanceMessageHandler
                → Processor.run()
                → NodeInstance.run() (throws exceptions)
                → StepByStepRunner catches exceptions
                → Creates outbox messages (waits, retries, parents)
```

### Key Components to Preserve

**Can be shared** (used by both old and new):
- `ExpressionEvaluator` (update to support scope)
- `SchemaValidator` (no changes needed)
- `ActivityRunnerProvider` (no changes needed)
- `DefinitionCache` (no changes needed)
- `WorkflowDescriptor`, `RuntimeDescriptor` (already exist)
- `NodePosition` (might need updates)

**Must be duplicated** (old vs new):
- Execution orchestration (`Processor.kt` vs `ExecutionOrchestrator.kt`)
- Node instances (`instances/*` vs new `Node` hierarchy)
- State management (`NodeState.kt` old vs new)
- State serialization (`WorkflowState` vs `InstanceState`)

---

## Migration Approach Options

### Option 1: New Package within lemline-core ⭐ **RECOMMENDED**

**Structure**:
```
lemline-core/
└── src/main/kotlin/com/lemline/core/
    ├── execution/           # NEW: Functional execution engine
    │   ├── ExecutionOrchestrator.kt
    │   ├── ErrorHandler.kt
    │   ├── StateHydrator.kt
    │   ├── nodes/           # NEW: Node implementations
    │   │   ├── Node.kt
    │   │   ├── DoTaskNode.kt
    │   │   ├── ForTaskNode.kt
    │   │   └── ...
    │   ├── state/           # NEW: State classes
    │   │   ├── NodeState.kt
    │   │   ├── DoTaskState.kt
    │   │   └── ...
    │   └── models/          # NEW: Execution models
    │       ├── FlowDirective.kt
    │       ├── InstanceState.kt
    │       ├── Scope.kt
    │       └── StepResult.kt
    │
    ├── processor/           # OLD: Keep for backward compatibility
    │   └── Processor.kt
    ├── instances/           # OLD: Keep for backward compatibility
    │   └── ...
    └── ... (shared components)
```

**Pros**:
- ✅ Single module, simpler build configuration
- ✅ Can share utilities easily (`expressions/`, `schemas/`, `activities/`)
- ✅ Gradual migration: new workflows use new engine, old continue with old
- ✅ Clear separation: `execution/` vs `processor/` + `instances/`
- ✅ No code duplication for shared components

**Cons**:
- ⚠️ Both implementations in same module (need careful dependency management)
- ⚠️ Larger module size temporarily

### Option 2: New Module (lemline-execution)

**Structure**:
```
lemline/
├── lemline-common/
├── lemline-core/            # OLD: Keep for backward compatibility
│   └── processor/, instances/, etc.
├── lemline-execution/       # NEW: Functional execution engine
│   └── src/main/kotlin/com/lemline/execution/
│       ├── ExecutionOrchestrator.kt
│       ├── nodes/, state/, models/
│       └── ...
└── lemline-runner/          # Update to use either core or execution
```

**Pros**:
- ✅ Complete isolation between old and new
- ✅ Clean separation of concerns
- ✅ Can version independently

**Cons**:
- ❌ Must duplicate or extract shared components
- ❌ More complex build configuration
- ❌ Harder to share utilities (expressions, schemas, activities)
- ❌ Creates confusion about which module to use

### Option 3: Feature Flag within Processor

**Structure**:
```
lemline-core/
└── src/main/kotlin/com/lemline/core/
    └── processor/
        ├── Processor.kt         # OLD implementation
        ├── ProcessorV2.kt       # NEW implementation
        └── ProcessorFactory.kt  # Switches based on config
```

**Pros**:
- ✅ Same module, same package
- ✅ Easy to toggle at runtime

**Cons**:
- ❌ Both implementations intertwined
- ❌ Hard to maintain clear separation
- ❌ Confusing code organization
- ❌ Difficult to eventually remove old code

---

## Recommended Approach

**Use Option 1: New Package within lemline-core**

This approach provides the best balance of:
- **Isolation**: Clear separation with `execution/` package
- **Sharing**: Can reuse utilities without duplication
- **Migration**: Gradual transition with feature toggle
- **Cleanup**: Easy to remove old code when ready (delete `processor/` and `instances/`)

### Implementation Strategy

1. **Phase 1: Create new execution package** (Weeks 1-6)
   - Create `com.lemline.core.execution` package
   - Implement all components in isolation
   - Write comprehensive tests
   - No impact on existing code

2. **Phase 2: Update shared components** (Weeks 7-8)
   - Update `ExpressionEvaluator` to support `Scope`
   - Ensure backward compatibility for old `Processor`
   - Add feature flag configuration

3. **Phase 3: Runner integration** (Weeks 9-11)
   - Create `ExecutionEngine` interface
   - Implement for both old and new
   - Update `StepByStepRunner` to use interface
   - Feature toggle to switch engines

4. **Phase 4: Testing and validation** (Weeks 12-14)
   - Run all tests with both engines
   - Performance comparison
   - Gradual rollout with monitoring

5. **Phase 5: Deprecation** (Weeks 15-16)
   - Mark old code as `@Deprecated`
   - Remove old implementation
   - Rename `execution` internals if needed

---

## Directory Structure

### Detailed Package Layout

```
lemline-core/src/main/kotlin/com/lemline/core/
│
├── execution/                          # NEW: Functional execution engine
│   │
│   ├── ExecutionOrchestrator.kt        # Main execution loop
│   ├── ErrorHandler.kt                 # Exception handling
│   ├── StateHydrator.kt                # State deserialization
│   │
│   ├── nodes/                          # Node implementations
│   │   ├── Node.kt                     # Base class
│   │   ├── FlowTaskNode.kt             # Base for flow tasks
│   │   ├── ActivityTaskNode.kt         # Base for activity tasks
│   │   ├── DoTaskNode.kt
│   │   ├── ForTaskNode.kt
│   │   ├── SwitchTaskNode.kt
│   │   ├── TryTaskNode.kt
│   │   ├── SetTaskNode.kt
│   │   ├── CallHttpTaskNode.kt
│   │   ├── EmitTaskNode.kt
│   │   ├── RunTaskNode.kt
│   │   ├── WaitTaskNode.kt
│   │   └── RaiseTaskNode.kt
│   │
│   ├── state/                          # State management
│   │   ├── NodeState.kt                # Base class
│   │   ├── DoTaskState.kt
│   │   ├── ForTaskState.kt
│   │   ├── SwitchTaskState.kt
│   │   ├── TryTaskState.kt
│   │   └── ActivityTaskState.kt
│   │
│   ├── models/                         # Core models
│   │   ├── FlowDirective.kt
│   │   ├── InstanceState.kt            # Serializable state
│   │   ├── StepResult.kt
│   │   ├── Scope.kt
│   │   ├── TaskDescriptor.kt
│   │   └── SerializedMutableState.kt
│   │
│   └── exceptions/                     # Execution-specific exceptions
│       └── ExecutionException.kt
│
├── expressions/                        # SHARED: Update for scope support
│   ├── ExpressionEvaluator.kt          # Update to accept Scope
│   ├── JQExpression.kt
│   └── scopes/                         # SHARED: Already exists
│       ├── WorkflowDescriptor.kt
│       └── ...
│
├── schemas/                            # SHARED: No changes needed
│   └── SchemaValidator.kt
│
├── activities/                         # SHARED: No changes needed
│   ├── ActivityRunnerProvider.kt
│   └── ...
│
├── definitions/                        # SHARED: No changes needed
│   └── DefinitionCache.kt
│
├── nodes/                              # SHARED: May need minor updates
│   ├── NodePosition.kt                 # Reuse or extend
│   └── ...
│
├── processor/                          # OLD: Keep for backward compatibility
│   └── Processor.kt                    # Mark @Deprecated in Phase 5
│
└── instances/                          # OLD: Keep for backward compatibility
    ├── DoInstance.kt                   # Mark @Deprecated in Phase 5
    └── ...
```

---

## Shared Components Strategy

### Components Requiring Updates

#### 1. ExpressionEvaluator

**Current Signature**:
```kotlin
object ExpressionEvaluator {
    fun evaluate(expression: String, input: JsonElement): JsonElement
}
```

**New Signature** (backward compatible):
```kotlin
object ExpressionEvaluator {
    // New method with scope support
    fun evaluate(
        expression: String,
        input: JsonElement,
        scope: Scope
    ): JsonElement

    // Keep old method for backward compatibility
    @Deprecated("Use evaluate with Scope parameter")
    fun evaluate(expression: String, input: JsonElement): JsonElement {
        // Call new method with empty scope
        return evaluate(expression, input, Scope.empty())
    }
}
```

**Location**: `com.lemline.core.expressions.ExpressionEvaluator`

#### 2. NodePosition

**Current**: Already exists in `com.lemline.core.nodes.NodePosition`

**Strategy**: Reuse as-is if compatible, or extend if needed

```kotlin
// Existing NodePosition should work for new implementation
// May need to add helper methods for serialization
```

#### 3. Scope Classes

**Current**: Partially exists in `com.lemline.core.expressions.scopes/`

**Strategy**: Extend existing classes

```kotlin
// Move from expressions/scopes/ to execution/models/ for clarity
// Or keep in expressions/scopes/ and import
```

### Dependency Management

**Ensure Clear Boundaries**:

```kotlin
// NEW code can use SHARED components
com.lemline.core.execution.*
  → ✅ com.lemline.core.expressions.*
  → ✅ com.lemline.core.schemas.*
  → ✅ com.lemline.core.activities.*
  → ❌ com.lemline.core.processor.*     // No dependencies on old code
  → ❌ com.lemline.core.instances.*

// OLD code remains unchanged
com.lemline.core.processor.*
com.lemline.core.instances.*
  → ✅ com.lemline.core.expressions.*   // Uses old API
  → ❌ com.lemline.core.execution.*     // No dependencies on new code
```

**Enforce with Architecture Tests** (ArchUnit):

```kotlin
@Test
fun `new execution code should not depend on old processor code`() {
    noClasses()
        .that().resideInAPackage("..execution..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..processor..", "..instances..")
        .check(importedClasses)
}

@Test
fun `old processor code should not depend on new execution code`() {
    noClasses()
        .that().resideInAnyPackage("..processor..", "..instances..")
        .should().dependOnClassesThat()
        .resideInAPackage("..execution..")
        .check(importedClasses)
}
```

---

## Feature Toggle Implementation

### Configuration Model

**Add to** `lemline-runner` configuration:

```kotlin
// com.lemline.runner.config.ExecutionConfig.kt
data class ExecutionConfig(
    /**
     * Execution engine version to use.
     * - "v1": Legacy exception-based engine (Processor.kt)
     * - "v2": Functional return-based engine (ExecutionOrchestrator.kt)
     */
    val engine: ExecutionEngine = ExecutionEngine.V1,

    /**
     * Enable experimental features.
     */
    val experimental: Boolean = false
)

enum class ExecutionEngine {
    V1,  // Old: Processor.kt
    V2   // New: ExecutionOrchestrator.kt
}
```

**YAML Configuration**:

```yaml
lemline:
  execution:
    engine: v2              # Use new functional engine
    experimental: false
```

**Environment Variable**:

```bash
LEMLINE_EXECUTION_ENGINE=v2
```

### Execution Engine Interface

**Create abstraction** in `lemline-runner`:

```kotlin
// com.lemline.runner.execution.WorkflowExecutor.kt
interface WorkflowExecutor {
    /**
     * Execute a single step of workflow execution.
     *
     * @return StepOutcome indicating what happened
     */
    suspend fun executeStep(
        definition: Workflow,
        instanceState: InstanceStateModel,
        dataset: JsonElement
    ): StepOutcome
}

sealed class StepOutcome {
    data class Continue(val nextState: InstanceStateModel, val dataset: JsonElement) : StepOutcome()
    data class Wait(val delay: Duration, val state: InstanceStateModel) : StepOutcome()
    data class Retry(val backoff: Duration, val state: InstanceStateModel) : StepOutcome()
    data class RunChild(val childWorkflow: WorkflowReference, val parentState: InstanceStateModel) : StepOutcome()
    data class Completed(val output: JsonElement) : StepOutcome()
    data class Failed(val error: WorkflowException) : StepOutcome()
}
```

### Implementation Adapters

**V1 Adapter** (wraps existing `Processor.kt`):

```kotlin
// com.lemline.runner.execution.v1.ProcessorExecutor.kt
class ProcessorExecutor(
    private val activityRunnerProvider: ActivityRunnerProvider
) : WorkflowExecutor {

    override suspend fun executeStep(
        definition: Workflow,
        instanceState: InstanceStateModel,
        dataset: JsonElement
    ): StepOutcome {
        val processor = Processor(
            workflowInfo = instanceState.toWorkflowInfo(),
            workflowState = instanceState.toWorkflowState(),
            secrets = instanceState.secrets
        )

        return try {
            // Run one step (will throw for waits, retries, etc.)
            processor.run()

            // If we get here, workflow completed
            StepOutcome.Completed(processor.output)

        } catch (e: WaitStartedException) {
            StepOutcome.Wait(e.delay, instanceState.updated())
        } catch (e: TaskRetriedException) {
            StepOutcome.Retry(e.backoff, instanceState.updated())
        } catch (e: RunWorkflowStartedException) {
            StepOutcome.RunChild(e.childRef, instanceState.updated())
        } catch (e: TaskCompletedException) {
            StepOutcome.Continue(instanceState.updated(), processor.currentDataset())
        } catch (e: WorkflowException) {
            StepOutcome.Failed(e)
        }
    }
}
```

**V2 Adapter** (wraps new `ExecutionOrchestrator`):

```kotlin
// com.lemline.runner.execution.v2.OrchestratorExecutor.kt
class OrchestratorExecutor(
    private val activityRunnerProvider: ActivityRunnerProvider
) : WorkflowExecutor {

    override suspend fun executeStep(
        definition: Workflow,
        instanceState: InstanceStateModel,
        dataset: JsonElement
    ): StepOutcome {
        // Hydrate workflow tree from instance state
        val (currentNode, currentDataset) = StateHydrator.hydrate(definition, instanceState)

        return try {
            // Execute one step (returns StepResult)
            val result = ExecutionOrchestrator.run(
                current = currentNode,
                dataset = currentDataset,
                flowDirective = FlowDirective.Continue
            )

            // Map StepResult to StepOutcome
            when {
                result.next == null -> StepOutcome.Completed(result.dataset)
                else -> StepOutcome.Continue(
                    nextState = instanceState.updated(result.next.position),
                    dataset = result.dataset
                )
            }

        } catch (e: WorkflowException) {
            StepOutcome.Failed(e)
        }
    }
}
```

### Factory

**Runtime selection**:

```kotlin
// com.lemline.runner.execution.WorkflowExecutorFactory.kt
@Singleton
class WorkflowExecutorFactory(
    private val executionConfig: ExecutionConfig,
    private val activityRunnerProvider: ActivityRunnerProvider
) {

    fun create(): WorkflowExecutor {
        return when (executionConfig.engine) {
            ExecutionEngine.V1 -> ProcessorExecutor(activityRunnerProvider)
            ExecutionEngine.V2 -> OrchestratorExecutor(activityRunnerProvider)
        }
    }
}
```

### StepByStepRunner Integration

**Update** `StepByStepRunner`:

```kotlin
// com.lemline.runner.StepByStepRunner.kt
@Singleton
class StepByStepRunner(
    private val executorFactory: WorkflowExecutorFactory,  // Inject factory
    // ... other dependencies
) {

    suspend fun run(
        definition: Workflow,
        instanceState: InstanceStateModel,
        dataset: JsonElement
    ) {
        val executor = executorFactory.create()  // Get executor based on config

        when (val outcome = executor.executeStep(definition, instanceState, dataset)) {
            is StepOutcome.Continue -> {
                // Send to workflows-out channel
                emit(outcome.nextState, outcome.dataset)
            }
            is StepOutcome.Wait -> {
                // Create wait outbox entry
                createWaitOutbox(outcome.state, outcome.delay)
            }
            is StepOutcome.Retry -> {
                // Create retry outbox entry
                createRetryOutbox(outcome.state, outcome.backoff)
            }
            is StepOutcome.RunChild -> {
                // Create parent outbox and child message
                createParentOutbox(outcome.parentState)
                emitChildWorkflow(outcome.childWorkflow)
            }
            is StepOutcome.Completed -> {
                // Mark workflow as completed
                completeWorkflow(outcome.output)
            }
            is StepOutcome.Failed -> {
                // Mark workflow as failed
                failWorkflow(outcome.error)
            }
        }
    }
}
```

---

## Gradual Migration Path

### Week-by-Week Implementation

#### Weeks 1-2: Foundation

**Tasks**:
- [ ] Create `com.lemline.core.execution` package structure
- [ ] Implement `FlowDirective`, `StepResult` models
- [ ] Implement base `NodeState<M>` class
- [ ] Write unit tests for models

**Deliverable**: Core models implemented and tested

**Risk**: Low (no impact on existing code)

#### Weeks 3-4: State Classes

**Tasks**:
- [ ] Implement `DoTaskState`, `ForTaskState`, `SwitchTaskState`
- [ ] Implement `TryTaskState`, `ActivityTaskState`
- [ ] Implement serialization (`SerializedMutableState`)
- [ ] Write unit tests for state classes

**Deliverable**: All state classes complete with serialization

**Risk**: Low (isolated implementation)

#### Weeks 5-6: Orchestration

**Tasks**:
- [ ] Implement `ExecutionOrchestrator` (enter, reEnter, continue, exitToUp, run)
- [ ] Implement `ErrorHandler` (handleException, retry, catch)
- [ ] Implement `StateHydrator`
- [ ] Write unit tests for orchestration

**Deliverable**: Core execution loop complete

**Risk**: Medium (complex logic, needs careful testing)

#### Weeks 7-8: Scope Support

**Tasks**:
- [ ] Implement `Scope`, `TaskDescriptor` models
- [ ] Update `ExpressionEvaluator` for scope support (backward compatible)
- [ ] Implement scope building logic
- [ ] Write unit tests for scope

**Deliverable**: Expression evaluation with scope support

**Risk**: Medium (must maintain backward compatibility)

#### Weeks 9-10: Node Implementations

**Tasks**:
- [ ] Implement `Node` base class with scope building
- [ ] Implement `DoTaskNode`, `ForTaskNode`, `SwitchTaskNode`, `TryTaskNode`
- [ ] Implement activity nodes (Set, CallHttp, Emit, Run, Wait, Raise)
- [ ] Write unit tests for all nodes

**Deliverable**: All node types implemented

**Risk**: Medium (integration with scope and state)

#### Weeks 11-12: Runner Integration

**Tasks**:
- [ ] Create `WorkflowExecutor` interface
- [ ] Implement `ProcessorExecutor` (V1 adapter)
- [ ] Implement `OrchestratorExecutor` (V2 adapter)
- [ ] Update `StepByStepRunner` to use executor
- [ ] Add configuration for engine selection

**Deliverable**: Both engines working through unified interface

**Risk**: High (integration with existing infrastructure)

#### Weeks 13-14: Testing & Validation

**Tasks**:
- [ ] Run all existing tests with V2 engine
- [ ] Add integration tests for complex workflows
- [ ] Performance benchmarking (V1 vs V2)
- [ ] Load testing with both engines

**Deliverable**: V2 engine validated and benchmarked

**Risk**: Medium (may discover issues requiring fixes)

#### Weeks 15-16: Production Rollout

**Tasks**:
- [ ] Deploy with V1 engine (default)
- [ ] Enable V2 for 10% of workflows (canary)
- [ ] Monitor metrics (latency, errors, memory)
- [ ] Gradually increase to 50%, then 100%
- [ ] Make V2 the default

**Deliverable**: V2 engine in production

**Risk**: High (production impact if issues found)

#### Weeks 17-18: Deprecation

**Tasks**:
- [ ] Mark old code as `@Deprecated`
- [ ] Remove V1 engine option
- [ ] Delete `processor/` and `instances/` packages
- [ ] Update documentation
- [ ] Clean up unused code

**Deliverable**: Old code removed, V2 is the only engine

**Risk**: Low (V2 already validated in production)

---

## Testing Strategy

### Unit Tests

**New Code Coverage**:
- `execution/*`: >95% coverage
- Focus on edge cases (error handling, state transitions)

**Existing Code**:
- Keep all existing tests passing with V1 engine
- No modifications needed

### Integration Tests

**Dual Engine Testing**:

```kotlin
@QuarkusTest
class WorkflowExecutionIntegrationTest : FunSpec({

    // Run same test with both engines
    listOf(ExecutionEngine.V1, ExecutionEngine.V2).forEach { engine ->

        context("with $engine engine") {

            test("should execute sequential workflow") {
                // Set engine via config
                setExecutionEngine(engine)

                // Run workflow
                val result = executeWorkflow(sequentialWorkflow, input)

                // Assertions
                result shouldBe expectedOutput
            }

            test("should execute for loop") {
                setExecutionEngine(engine)
                val result = executeWorkflow(forLoopWorkflow, input)
                result shouldBe expectedOutput
            }

            // ... more tests
        }
    }
})
```

### Performance Tests

**Benchmarking Suite**:

```kotlin
@QuarkusTest
class PerformanceBenchmark {

    @Test
    fun `compare V1 vs V2 throughput`() {
        val workflow = createComplexWorkflow()
        val iterations = 1000

        // V1 benchmark
        val v1Duration = measureTime {
            repeat(iterations) {
                executeWithV1(workflow)
            }
        }

        // V2 benchmark
        val v2Duration = measureTime {
            repeat(iterations) {
                executeWithV2(workflow)
            }
        }

        println("V1: ${iterations / v1Duration.inWholeSeconds} workflows/sec")
        println("V2: ${iterations / v2Duration.inWholeSeconds} workflows/sec")

        // V2 should be comparable or better
        v2Duration.inWholeMilliseconds shouldBeLessThan v1Duration.inWholeMilliseconds * 1.1
    }

    @Test
    fun `compare state serialization size`() {
        val workflow = createLargeWorkflow()

        val v1State = executeWithV1UntilCheckpoint(workflow)
        val v2State = executeWithV2UntilCheckpoint(workflow)

        val v1Size = serialize(v1State).size
        val v2Size = serialize(v2State).size

        println("V1 state: $v1Size bytes")
        println("V2 state: $v2Size bytes")
        println("Reduction: ${100 - (v2Size * 100 / v1Size)}%")

        // V2 should be significantly smaller
        v2Size shouldBeLessThan (v1Size * 0.5)  // At least 50% reduction
    }
}
```

### Compatibility Tests

**Database Compatibility**:
- Test with PostgreSQL, MySQL, H2
- Ensure serialization works correctly

**Broker Compatibility**:
- Test with Kafka, RabbitMQ
- Ensure message format compatible

---

## Rollback Plan

### Emergency Rollback Procedure

**If critical issues discovered in production**:

1. **Immediate**: Switch configuration to V1
   ```yaml
   lemline:
     execution:
       engine: v1  # Rollback to old engine
   ```

2. **Deploy**: Restart services with V1 configuration

3. **Monitor**: Verify workflows executing normally

4. **Investigate**: Analyze logs and metrics to find root cause

5. **Fix**: Address issues in V2 code

6. **Re-test**: Validate fix in staging

7. **Re-deploy**: Gradual rollout again

### Rollback Triggers

**Automatic rollback if**:
- Error rate > 1% (compared to <0.1% baseline)
- P99 latency > 2x baseline
- Memory usage > 150% baseline
- Critical workflow failures

### Monitoring Metrics

**Key Metrics to Track**:
- Workflow execution success rate (by engine)
- Step execution latency P50, P95, P99 (by engine)
- Memory usage per workflow instance (by engine)
- Message serialization size (by engine)
- Database query performance
- Broker throughput

**Alerting**:
- Alert if V2 error rate > V1 error rate + 0.5%
- Alert if V2 latency > V1 latency * 1.5
- Alert if V2 memory > V1 memory * 1.3

---

## Migration Checklist

### Pre-Implementation

- [ ] Review and approve this migration strategy
- [ ] Set up project tracking (Jira, GitHub Projects, etc.)
- [ ] Assign team members to work packages
- [ ] Schedule weekly sync meetings
- [ ] Create feature branch: `feature/execution-v2`

### Phase 1: Foundation (Weeks 1-6)

- [ ] Create `com.lemline.core.execution` package structure
- [ ] Implement core models (`FlowDirective`, `StepResult`, `Scope`)
- [ ] Implement state classes with immutable/mutable separation
- [ ] Implement orchestration functions
- [ ] Update `ExpressionEvaluator` for scope support (backward compatible)
- [ ] Unit tests: >95% coverage
- [ ] Code review and approval

### Phase 2: Node Implementation (Weeks 7-10)

- [ ] Implement `Node` base class
- [ ] Implement all flow task nodes
- [ ] Implement all activity task nodes
- [ ] Implement scope building and merging
- [ ] Unit tests for each node type
- [ ] Integration tests for node interactions
- [ ] Code review and approval

### Phase 3: Runner Integration (Weeks 11-12)

- [ ] Create `WorkflowExecutor` interface
- [ ] Implement V1 and V2 adapters
- [ ] Update `StepByStepRunner` to use executor
- [ ] Add configuration for engine selection
- [ ] Update `InstanceMessageHandler` for V2 serialization
- [ ] Integration tests with both engines
- [ ] Code review and approval

### Phase 4: Testing (Weeks 13-14)

- [ ] Run all existing tests with V2 engine
- [ ] Add comprehensive integration tests
- [ ] Performance benchmarking (V1 vs V2)
- [ ] Load testing with realistic workflows
- [ ] Database compatibility testing
- [ ] Broker compatibility testing
- [ ] Security review
- [ ] Documentation update

### Phase 5: Production Rollout (Weeks 15-16)

- [ ] Deploy to staging with V2 as default
- [ ] Run staging tests for 1 week
- [ ] Deploy to production with V1 as default
- [ ] Enable V2 for canary workflows (10%)
- [ ] Monitor metrics for 2 days
- [ ] Increase to 50% if metrics good
- [ ] Monitor for 2 more days
- [ ] Increase to 100%
- [ ] Make V2 the default
- [ ] Monitor for 1 week

### Phase 6: Cleanup (Weeks 17-18)

- [ ] Remove V1 engine option from config
- [ ] Mark old code as `@Deprecated`
- [ ] Create migration guide for external users
- [ ] Wait 2 weeks for feedback
- [ ] Delete `processor/` and `instances/` packages
- [ ] Update all documentation
- [ ] Merge feature branch to main
- [ ] Release notes and announcement

---

## Conclusion

The recommended approach is to **create a new package `com.lemline.core.execution` within the existing `lemline-core` module**. This provides:

✅ **Clear Separation**: New code isolated in `execution/` package
✅ **Easy Sharing**: Reuse utilities without duplication
✅ **Gradual Migration**: Feature toggle for safe rollout
✅ **Simple Cleanup**: Delete old packages when ready

**Key Success Factors**:
1. Strict dependency boundaries (enforce with ArchUnit)
2. Comprehensive testing (unit, integration, performance)
3. Backward compatible changes to shared components
4. Gradual production rollout with monitoring
5. Clear rollback plan

**Timeline**: 16-18 weeks from start to production default

**Next Steps**:
1. Review and approve this migration strategy
2. Set up project tracking and assign tasks
3. Create feature branch: `feature/execution-v2`
4. Begin Week 1 implementation
