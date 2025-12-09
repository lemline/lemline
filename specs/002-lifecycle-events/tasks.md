# Tasks: Workflow Lifecycle Events

**Input**: Design documents from `/specs/002-lifecycle-events/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Tests are included per Lemline's constitution (Principle II: Comprehensive Testing).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **lemline-core**: `lemline-core/src/main/kotlin/com/lemline/core/`
- **lemline-runner**: `lemline-runner/src/main/kotlin/com/lemline/runner/`
- **lemline-core tests**: `lemline-core/src/test/kotlin/com/lemline/core/`
- **lemline-runner tests**: `lemline-runner/src/test/kotlin/com/lemline/runner/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration and constants for lifecycle events channel

- [ ] T001 Add lifecycle events topic constant `LIFECYCLE_EVENTS_TOPIC_DEFAULT = "lemline-lifecycle-events"` in lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfigConstants.kt
- [ ] T002 Add `LifecycleEventsChannelConfig` interface with producer/consumer config in lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt
- [ ] T003 [P] Add Kafka lifecycle events topic configuration in `KafkaConfig` interface in lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt
- [ ] T004 [P] Add RabbitMQ lifecycle events exchange configuration in `RabbitMQConfig` interface in lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt
- [ ] T005 Update `toQuarkusProperties()` to generate SmallRye channel config for `lifecycleevents-out` in lemline-runner/src/main/kotlin/com/lemline/runner/config/LemlineConfiguration.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core interfaces and types that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T006 Create `LifecycleEventType` enum with all 7 event types (workflow.started/completed/faulted, task.started/completed/faulted/retried) in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventType.kt
- [ ] T007 Create `LifecycleEventHook` interface in lemline-core with callbacks: `onWorkflowStarted`, `onWorkflowCompleted`, `onWorkflowFaulted`, `onTaskStarted`, `onTaskCompleted`, `onTaskFaulted`, `onTaskRetried` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/LifecycleEventHook.kt
- [ ] T008 Create `LifecycleEventData` sealed class hierarchy for event payloads (WorkflowStartedData, WorkflowCompletedData, WorkflowFaultedData, TaskStartedData, TaskCompletedData, TaskFaultedData, TaskRetriedData) in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/LifecycleEventData.kt
- [ ] T009 Create `LifecycleEventEmitter` class with `@Channel("lifecycleevents-out")` injection and `suspend fun emit(event: CloudEvent)` method using fire-and-forget pattern in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventEmitter.kt
- [ ] T010 Create `LifecycleEventHookImpl` implementing `LifecycleEventHook` that builds CloudEvents and delegates to `LifecycleEventEmitter` in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventHookImpl.kt

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Monitor Workflow Execution Progress (Priority: P1) 🎯 MVP

**Goal**: Emit `workflow.started`, `workflow.completed`, and `workflow.faulted` CloudEvents when workflows transition through lifecycle states

**Independent Test**: Start a workflow and verify CloudEvents are published to the lifecycle channel at workflow start and completion/failure

### Tests for User Story 1

- [ ] T011 [P] [US1] Unit test for `LifecycleEventEmitter.emit()` with mock channel in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventEmitterTest.kt
- [ ] T012 [P] [US1] Unit test for `LifecycleEventHookImpl` workflow event building (started/completed/faulted CloudEvent structure) in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventHookImplTest.kt
- [ ] T013 [P] [US1] Integration test for workflow.started emission on workflow begin in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/WorkflowLifecycleEventIntegrationTest.kt
- [ ] T014 [P] [US1] Integration test for workflow.completed emission on successful completion in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/WorkflowLifecycleEventIntegrationTest.kt
- [ ] T015 [P] [US1] Integration test for workflow.faulted emission on unhandled error in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/WorkflowLifecycleEventIntegrationTest.kt

### Implementation for User Story 1

- [ ] T016 [US1] Add `lifecycleEventHook` parameter to `StepByStepOrchestrator` constructor with default no-op implementation in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T017 [US1] Add `onWorkflowStarted` hook call when first `ResumeFromTask` at root position is processed in `StepByStepOrchestrator.resumeFromTask()` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T018 [US1] Add `onWorkflowCompleted` hook call when `WorkflowCompleted` outcome is produced in `StepByStepOrchestrator` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T019 [US1] Add `onWorkflowFaulted` hook call when `WorkflowFailed` outcome is produced in `StepByStepOrchestrator` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T020 [US1] Implement workflow CloudEvent building in `LifecycleEventHookImpl`: source URI, type, Lemline extensions, data payload per data-model.md in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventHookImpl.kt
- [ ] T021 [US1] Wire `LifecycleEventHookImpl` into `WorkflowCommandHandler` and pass to orchestrator in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt

**Checkpoint**: Workflow lifecycle events (started/completed/faulted) are emitted and testable independently

---

## Phase 4: User Story 2 - Track Task-Level Execution (Priority: P2)

**Goal**: Emit `task.started`, `task.completed`, `task.faulted`, and `task.retried` CloudEvents for granular task observability

**Independent Test**: Run a workflow with multiple tasks and verify each task emits appropriate lifecycle events

### Tests for User Story 2

- [ ] T022 [P] [US2] Unit test for task event building in `LifecycleEventHookImpl` (started/completed/faulted/retried CloudEvent structure with JSON Pointer task reference) in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventHookImplTest.kt
- [ ] T023 [P] [US2] Integration test for task.started emission when ResumeFromTask begins in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/TaskLifecycleEventIntegrationTest.kt
- [ ] T024 [P] [US2] Integration test for task.completed emission on task success in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/TaskLifecycleEventIntegrationTest.kt
- [ ] T025 [P] [US2] Integration test for task.faulted emission on task failure in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/TaskLifecycleEventIntegrationTest.kt
- [ ] T026 [P] [US2] Integration test for task.retried emission on retry attempt in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/TaskLifecycleEventIntegrationTest.kt

### Implementation for User Story 2

- [ ] T027 [US2] Add `onTaskStarted` hook call when any `ResumeFromTask` begins execution in `StepByStepOrchestrator.resumeFromTask()` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T028 [US2] Add `onTaskCompleted` hook call when task returns success in `StepByStepOrchestrator` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T029 [US2] Add `onTaskFaulted` hook call when task throws exception in `StepByStepOrchestrator` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T030 [US2] Add `onTaskRetried` hook call when retry is scheduled in `StepByStepOrchestrator` in lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt
- [ ] T031 [US2] Implement task CloudEvent building in `LifecycleEventHookImpl`: JSON Pointer task reference, workflow context, timestamps per data-model.md in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventHookImpl.kt

**Checkpoint**: Task lifecycle events (started/completed/faulted/retried) are emitted and testable independently

---

## Phase 5: User Story 3 - Build External Integrations (Priority: P3)

**Goal**: Ensure lifecycle events are consumable by external systems via the dedicated channel with proper CloudEvents format

**Independent Test**: Configure an external consumer on the lifecycle channel and verify it receives events in CloudEvents format

### Tests for User Story 3

- [ ] T032 [P] [US3] Contract test validating all lifecycle events conform to CloudEvents v1.0 spec against contracts/lifecycle-events.cloudevents.json in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventContractTest.kt
- [ ] T033 [P] [US3] Integration test with InMemory broker verifying external consumer receives events in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/ExternalConsumerIntegrationTest.kt
- [ ] T034 [US3] Integration test with Kafka broker verifying lifecycle events topic consumption in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/ExternalConsumerKafkaTest.kt
- [ ] T035 [US3] Integration test with RabbitMQ broker verifying lifecycle events exchange consumption in lemline-runner/src/test/kotlin/com/lemline/runner/messaging/lifecycle/ExternalConsumerRabbitMQTest.kt

### Implementation for User Story 3

- [ ] T036 [US3] Implement deterministic IDV7 event ID generation using `nodeStack.deriveIdempotentId("-lifecycle-${eventType}")` pattern in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventHookImpl.kt
- [ ] T037 [US3] Add graceful error handling in `LifecycleEventEmitter.emit()` - log warning on channel unavailable, never throw in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventEmitter.kt
- [ ] T038 [US3] Add `@IfBuildProperty` conditional for lifecycle events producer enablement in lemline-runner/src/main/kotlin/com/lemline/runner/messaging/lifecycle/LifecycleEventEmitter.kt

**Checkpoint**: External systems can consume lifecycle events via dedicated channel

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, validation, and cleanup

- [ ] T039 [P] Add lifecycle events section to lemline-runner/docs/runner-messaging.md documenting the new channel and event types
- [ ] T040 [P] Add lifecycle events configuration section to lemline-runner/docs/runner-configuration.md
- [ ] T041 Validate quickstart.md scenarios work end-to-end with real Kafka/RabbitMQ
- [ ] T042 [P] Performance test: verify lifecycle event emission adds <5% overhead using existing benchmark suite
- [ ] T043 Code review and cleanup: ensure all lifecycle event code follows Kotlin conventions per constitution

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Independent of US1 (uses same hook infrastructure)
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Independent (validates what US1/US2 emit)

### Within Each User Story

- Tests should be written first (TDD approach per constitution)
- Core orchestrator hooks (lemline-core) before runner implementation
- Hook implementation before wiring into command handler
- Integration tests validate end-to-end

### Parallel Opportunities

**Phase 1 Setup:**
```
T003 (Kafka config) || T004 (RabbitMQ config)
```

**Phase 2 Foundational:**
```
T006 (LifecycleEventType) || T007 (LifecycleEventHook interface) || T008 (LifecycleEventData)
Then: T009 (Emitter) || T010 (HookImpl) - once above complete
```

**Phase 3 User Story 1:**
```
T011 || T012 || T013 || T014 || T015 (all tests in parallel)
Then: T016 → T017 → T018 → T019 (orchestrator changes sequential)
Then: T020 → T021 (runner wiring)
```

**Phase 4 User Story 2:**
```
T022 || T023 || T024 || T025 || T026 (all tests in parallel)
Then: T027 → T028 → T029 → T030 (orchestrator changes sequential)
Then: T031 (runner implementation)
```

**Phase 5 User Story 3:**
```
T032 || T033 (contract + InMemory tests)
Then: T034 || T035 (broker-specific tests)
Then: T036 → T037 → T038 (implementation)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (5 tasks)
2. Complete Phase 2: Foundational (5 tasks)
3. Complete Phase 3: User Story 1 (11 tasks)
4. **STOP and VALIDATE**: Run workflow, verify `workflow.started` and `workflow.completed` events
5. Deploy/demo if ready - basic workflow observability is now functional

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → **MVP: Workflow-level observability**
3. Add User Story 2 → Test independently → **Enhanced: Task-level debugging**
4. Add User Story 3 → Test independently → **Complete: External integration ready**
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers after Foundational phase:
- Developer A: User Story 1 (workflow events)
- Developer B: User Story 2 (task events)
- Developer C: User Story 3 (external integration/contracts)

---

## Notes

- All CloudEvent building follows data-model.md schemas
- Event IDs use deterministic IDV7 derivation for idempotency
- Fire-and-forget pattern: emit failures log warning, never throw
- `@IfBuildProperty` allows disabling lifecycle events for performance-critical deployments
- No database persistence - events go directly to messaging channel
