# Research: Workflow Lifecycle Events

**Feature**: 002-lifecycle-events
**Date**: 2025-12-08

## 1. Serverless Workflow Specification Compliance

### Decision: Implement subset of lifecycle events matching Lemline's current capabilities

**Rationale**: The Serverless Workflow spec defines 18 lifecycle event types (9 workflow + 9 task). Lemline currently supports a subset of workflow features, so we implement only the events that correspond to existing functionality.

**Events to Implement**:

| Event Type | Serverless Workflow Type | Triggered When |
|------------|--------------------------|----------------|
| `io.serverlessworkflow.workflow.started.v1` | Workflow Started | New workflow instance begins |
| `io.serverlessworkflow.workflow.completed.v1` | Workflow Completed | Workflow completes successfully |
| `io.serverlessworkflow.workflow.faulted.v1` | Workflow Faulted | Unhandled error terminates workflow |
| `io.serverlessworkflow.task.started.v1` | Task Started | Task execution begins |
| `io.serverlessworkflow.task.completed.v1` | Task Completed | Task completes successfully |
| `io.serverlessworkflow.task.faulted.v1` | Task Faulted | Task fails with error |
| `io.serverlessworkflow.task.retried.v1` | Task Retried | Retry attempt begins |

**Not Implemented** (features not in Lemline):
- `workflow.suspended` / `workflow.resumed` - No manual suspension
- `workflow.cancelled` - No cancellation API
- `workflow.correlation-started/completed` - No correlation tracking
- `task.created` - Tasks created and started atomically
- `task.suspended` / `task.resumed` / `task.cancelled` - No task-level suspension

**Alternatives Considered**:
- Implement all 18 events with stubs → Rejected: violates YAGNI principle
- Emit only workflow-level events → Rejected: task events needed for debugging

---

## 2. CloudEvents Format and Structure

### Decision: Use JSON structured content mode with Lemline workflow extensions

**Rationale**: Lemline already uses CloudEvents SDK with JSON structured content mode for the emit task. Reusing this pattern ensures consistency and leverages existing infrastructure.

**CloudEvent Structure**:

```json
{
  "specversion": "1.0",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "source": "urn:lemline:workflow:{namespace}:{name}:{version}",
  "type": "io.serverlessworkflow.workflow.started.v1",
  "time": "2025-12-08T10:30:00Z",
  "datacontenttype": "application/json",
  "lemlineworkflowid": "550e8400-e29b-41d4-a716-446655440001",
  "lemlineworkflownamespace": "default",
  "lemlineworkflowname": "my-workflow",
  "lemlineworkflowversion": "1.0.0",
  "data": {
    "name": "default/my-workflow:1.0.0",
    "startedAt": "2025-12-08T10:30:00Z",
    "definition": {
      "namespace": "default",
      "name": "my-workflow",
      "version": "1.0.0"
    }
  }
}
```

**Extension Attributes** (consistent with existing CloudEvents emission):
- `lemlineworkflowid` - UUID v7 workflow instance ID
- `lemlineworkflownamespace` - Workflow namespace
- `lemlineworkflowname` - Workflow name
- `lemlineworkflowversion` - Workflow version

**Alternatives Considered**:
- Binary content mode → Rejected: Less readable, harder to debug
- Custom event format → Rejected: Breaks CloudEvents interoperability

---

## 3. Messaging Channel Architecture

### Decision: Dedicated `lemline-lifecycle-events` channel with producer-only configuration

**Rationale**: Lifecycle events are for external consumption (monitoring, auditing). They should not be mixed with internal workflow commands/events channels. The existing dual-channel pattern provides a template.

**Channel Configuration**:
```yaml
lemline:
  messaging:
    lifecycleevents:
      producer:
        enabled: true   # Enable emission
      consumer:
        enabled: false  # No internal consumption

    kafka:
      lifecycleevents:
        topic: lemline-lifecycle-events
```

**Topic Names**:
- Kafka: `lemline-lifecycle-events`
- RabbitMQ: `lemline-lifecycle-events` exchange

**Alternatives Considered**:
- Reuse `lemline-cloudevents` channel → Rejected: Mixes user emit events with system lifecycle events
- Add to `lemline-events` channel → Rejected: Internal channel not for external consumption
- Database persistence + outbox → Rejected: Adds latency, lifecycle events are observability data

---

## 4. Hook Points for Event Emission

### Decision: Capture lifecycle hooks in `StepByStepOrchestrator` (lemline-core), emit from runner

**Rationale**: This provides accurate semantics by distinguishing between task scheduling and actual execution:
- `TaskScheduled` internal event = task queued (may wait if queue is full) - NOT `task.started`
- `ResumeFromTask` command processing = task actually begins - THIS is `task.started`

By placing hooks in `StepByStepOrchestrator`, we capture lifecycle events at the precise moment of state transition. The runner handles actual CloudEvent emission via the messaging infrastructure.

**Hook Points Analysis**:

| Location | Pros | Cons | Decision |
|----------|------|------|----------|
| `StepByStepOrchestrator` (lemline-core) | Accurate semantics, captures exact state transitions | Core must expose hook interface | **PRIMARY** |
| `WorkflowCommandHandler` (lemline-runner) | Has messaging access | Less precise timing (scheduling vs execution) | Emission only |
| `WorkflowEventHandler` | Has DB transaction | Too late, after events channel | Not used |

**Implementation Approach**:
1. Define `LifecycleEventHook` interface in lemline-core
2. Add hook callbacks to `StepByStepOrchestrator` at state transition points
3. Runner implements the hook interface, builds CloudEvents, and emits via `LifecycleEventEmitter`
4. Use fire-and-forget pattern (don't await send completion)

**State Transition to Lifecycle Event Mapping**:

| Orchestrator Hook Point | Lifecycle Event | Notes |
|------------------------|-----------------|-------|
| First `ResumeFromTask` (root position) | `workflow.started` | Workflow actually begins |
| Any `ResumeFromTask` | `task.started` | Task actually begins execution |
| Task returns success | `task.completed` | Task finishes successfully |
| Task throws exception | `task.faulted` | Task fails |
| Retry scheduled | `task.retried` | Before retry attempt |
| `WorkflowCompleted` outcome | `workflow.completed` | Workflow finishes successfully |
| `WorkflowFailed` outcome | `workflow.faulted` | Workflow fails with unhandled error |

**Alternatives Considered**:
- Runner-only emission from `WorkflowCommandHandler` → Rejected: `TaskScheduled` ≠ `task.started` semantically
- AOP/interceptors → Rejected: Adds complexity, harder to test
- Event sourcing pattern → Rejected: Over-engineering for observability use case

---

## 5. Event ID Generation

### Decision: Use deterministic IDV7 derivation for idempotency

**Rationale**: Lemline uses IDV7 (UUID v7) for all entity IDs. Lifecycle events should use the same pattern, with deterministic derivation to ensure idempotent message delivery.

**ID Generation Pattern**:
```kotlin
// Derive from workflow instance + position + step + event type
val eventId = nodeStack.deriveIdempotentId("-lifecycle-${eventType}")
```

This ensures:
- Same event ID for replayed workflows
- Broker-level deduplication support
- No duplicate lifecycle events on retry

**Alternatives Considered**:
- Random UUID per event → Rejected: No idempotency guarantee
- Sequence numbers → Rejected: Requires state tracking

---

## 6. Performance Characteristics

### Decision: Fire-and-forget with async emission, no blocking

**Rationale**: Lifecycle events are observability data. They must not impact workflow execution latency.

**Performance Requirements**:
- Event emission must complete in <1ms (just queue to emitter buffer)
- No database operations
- No synchronous acknowledgment waiting
- Graceful degradation if channel unavailable

**Implementation**:
```kotlin
// Fire-and-forget pattern
suspend fun emitLifecycleEvent(event: CloudEvent) {
    try {
        emitter.send(event)  // Async, returns immediately
    } catch (e: Exception) {
        logger.warn("Failed to emit lifecycle event: ${e.message}")
        // Don't throw - lifecycle events are best-effort
    }
}
```

**Alternatives Considered**:
- Synchronous acknowledgment → Rejected: Adds latency
- Buffered batch emission → Rejected: Adds complexity, delays events

---

## 7. Testing Strategy

### Decision: Three-tier testing with unit, integration, and contract tests

**Rationale**: Following Lemline's testing constitution, lifecycle events require comprehensive testing at multiple levels.

**Test Levels**:

1. **Unit Tests** (`LifecycleEventEmitterTest.kt`)
   - Event building logic
   - CloudEvents attribute population
   - Extension attribute handling
   - Error handling (channel unavailable)

2. **Integration Tests** (`LifecycleEventIntegrationTest.kt`)
   - End-to-end with InMemory broker
   - End-to-end with Kafka
   - End-to-end with RabbitMQ
   - Event delivery verification

3. **Contract Tests**
   - CloudEvents v1.0 compliance
   - Serverless Workflow event type compliance
   - JSON schema validation

**Test Patterns**:
```kotlin
@QuarkusTest
@TestProfile(InMemoryProfile::class)
class LifecycleEventIntegrationTest : FunSpec({
    test("should emit workflow.started when workflow begins") {
        // Given: workflow definition
        // When: start workflow
        // Then: lifecycle channel receives workflow.started event
    }
})
```

**Alternatives Considered**:
- Only integration tests → Rejected: Slower feedback, harder to isolate issues
- Mock-heavy unit tests → Rejected: May miss integration issues

---

## 8. Configuration Schema

### Decision: Extend existing `LemlineConfiguration` with lifecycle channel config

**Rationale**: Follow existing configuration patterns for consistency. Lifecycle events use the same structure as commands/events/cloudevents channels.

**Configuration Interface**:
```kotlin
interface LifecycleEventsChannelConfig {
    fun producer(): ProducerConfig
    fun consumer(): ConsumerConfig  // Always disabled
}

interface ProducerConfig {
    fun enabled(): Boolean  // Default: true
}
```

**Default Values**:
- `producer.enabled`: `true` (lifecycle events enabled by default)
- `consumer.enabled`: `false` (no internal consumption)
- Topic: `lemline-lifecycle-events`

**Environment Variables**:
- `LEMLINE_MESSAGING_LIFECYCLEEVENTS_PRODUCER_ENABLED`

**Alternatives Considered**:
- Separate config file → Rejected: Inconsistent with existing pattern
- No configuration → Rejected: Users may want to disable for performance

---

## Summary

| Decision Area | Choice | Key Rationale |
|---------------|--------|---------------|
| Event Types | 7 of 18 spec events | Match Lemline's current capabilities |
| Format | CloudEvents JSON structured | Consistency with existing emit task |
| Channel | Dedicated `lemline-lifecycle-events` | Separation of concerns |
| Hook Point | `StepByStepOrchestrator` hooks (core) + runner emission | Accurate semantics (scheduling ≠ execution) |
| ID Generation | Deterministic IDV7 derivation | Idempotency support |
| Performance | Fire-and-forget async | No workflow execution impact |
| Testing | Unit + Integration + Contract | Constitution compliance |
| Configuration | Extend `LemlineConfiguration` | Consistency with existing patterns |
