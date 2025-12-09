# Implementation Plan: Workflow Lifecycle Events

**Branch**: `002-lifecycle-events` | **Date**: 2025-12-08 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-lifecycle-events/spec.md`

## Summary

Implement Serverless Workflow Lifecycle Events by publishing CloudEvents to a dedicated messaging channel (`lemline-lifecycle-events`) in real-time as workflows and tasks transition through states. The implementation uses a two-layer architecture:

1. **lemline-core**: Defines `LifecycleEventHook` interface and adds hook callbacks in `StepByStepOrchestrator` at precise state transition points
2. **lemline-runner**: Implements the hook interface, builds CloudEvents, and emits via `LifecycleEventEmitter`

This separation ensures accurate semantics where `task.started` fires when execution actually begins (via `ResumeFromTask`), not when the task is merely scheduled.

**Events to implement:**
- Workflow: `started`, `completed`, `faulted`
- Task: `started`, `completed`, `faulted`, `retried`

## Technical Context

**Language/Version**: Kotlin 2.2.10, Java 17
**Primary Dependencies**: Quarkus, SmallRye Reactive Messaging, CloudEvents SDK (io.cloudevents)
**Storage**: N/A (fire-and-forget to messaging channel, no database persistence)
**Testing**: Kotest 5.9.1, JUnit 5 (via Quarkus), MockK 1.13.9, Kotlinx Coroutines 1.10.2
**Target Platform**: Linux server (JVM and GraalVM native)
**Project Type**: Multi-module Gradle project (lemline-core, lemline-runner)
**Performance Goals**: <5% overhead on workflow execution, events published within 100ms of state transition
**Constraints**: Fire-and-forget pattern (non-blocking), must work with Kafka and RabbitMQ
**Scale/Scope**: Support >10,000 msg/sec throughput per worker (existing performance target)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| **I. Code Quality First** | PASS | Clean separation of concerns (core captures, runner emits), follows existing patterns |
| **II. Comprehensive Testing** | PASS | Unit tests for hook interface, integration tests with all brokers |
| **III. User Experience Consistency** | PASS | Events follow CloudEvents v1.0 spec, configurable via standard config hierarchy |
| **IV. Performance by Design** | PASS | Fire-and-forget pattern, no database access, async emission |
| **V. Simplicity and Minimalism** | PASS | Minimal interface in core, reuses existing CloudEvents infrastructure in runner |

**No violations requiring justification.**

## Project Structure

### Documentation (this feature)

```text
specs/002-lifecycle-events/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (CloudEvents schemas)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
lemline-core/src/main/kotlin/com/lemline/core/
├── orchestrator/
│   ├── StepByStepOrchestrator.kt     # MODIFIED: Add lifecycle hook callbacks
│   └── LifecycleEventHook.kt         # NEW: Hook interface for lifecycle events

lemline-runner/src/main/kotlin/com/lemline/runner/
├── messaging/
│   ├── lifecycle/                     # NEW: Lifecycle events module
│   │   ├── LifecycleEventEmitter.kt   # NEW: Emitter for lifecycle CloudEvents
│   │   ├── LifecycleEventType.kt      # NEW: Event type constants
│   │   └── LifecycleEventHookImpl.kt  # NEW: Implements LifecycleEventHook
│   └── commands/
│       └── WorkflowCommandHandler.kt  # MODIFIED: Wire up lifecycle hook
├── config/
│   ├── LemlineConfiguration.kt        # MODIFIED: Add lifecycle channel config
│   └── LemlineConfigConstants.kt      # MODIFIED: Add lifecycle topic constant

lemline-runner/src/main/resources/
└── db/migration/                      # NO CHANGES (no database persistence)

lemline-core/src/test/kotlin/com/lemline/core/
└── orchestrator/
    └── LifecycleEventHookTest.kt      # NEW: Unit tests for hook callbacks

lemline-runner/src/test/kotlin/com/lemline/runner/
└── messaging/
    └── lifecycle/                     # NEW: Lifecycle event tests
        ├── LifecycleEventEmitterTest.kt
        └── LifecycleEventIntegrationTest.kt
```

**Structure Decision**: Two-module approach with clean separation:
- **lemline-core**: Defines minimal `LifecycleEventHook` interface and integrates hooks into `StepByStepOrchestrator`
- **lemline-runner**: Implements hook, builds CloudEvents, manages configuration and emission

This maintains the existing boundary where core handles workflow semantics and runner handles infrastructure concerns.

## Complexity Tracking

> **No violations to justify** - Implementation follows existing patterns with clean module boundaries.
