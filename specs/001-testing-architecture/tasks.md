# Tasks: End-to-End Testing Framework

**Input**: Design documents from `/specs/001-testing-architecture/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Module**: `lemline-testing/src/main/kotlin/com/lemline/testing/`
- **Profiles**: `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/`
- **Resources**: `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/resources/`
- **E2E Tests**: `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/`
- **Runner Unit Tests**: `lemline-runner/src/test/kotlin/com/lemline/runner/activities/`

---

## Phase 1: Setup (Module Infrastructure)

**Purpose**: Create the lemline-testing module and basic structure

- [ ] T001 Create `lemline-testing` directory structure per plan.md
- [ ] T002 Create `lemline-testing/build.gradle.kts` with dependencies (Quarkus, Testcontainers, Kotest, CloudEvents)
- [ ] T003 Add `lemline-testing` to `settings.gradle.kts` include list
- [ ] T004 [P] Create package directories: `com.lemline.testing`, `com.lemline.testing.profiles`, `com.lemline.testing.profiles.resources`

---

## Phase 2: Foundational (Core Infrastructure)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Test Resources (Testcontainers)

- [ ] T005 [P] Create `KafkaTestResource.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/resources/` (extract from lemline-runner)
- [ ] T006 [P] Create `RabbitMQTestResource.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/resources/` (extract from lemline-runner)
- [ ] T007 [P] Create `PostgresTestResource.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/resources/` (extract from lemline-runner)
- [ ] T008 [P] Create `MySQLTestResource.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/resources/` (extract from lemline-runner)

### Composable Test Profiles

- [ ] T009 Create `BaseBrokerTestProfile.kt` abstract class in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/` with common config
- [ ] T010 [P] Create `KafkaPostgresProfile.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/` (inherits BaseBrokerTestProfile)
- [ ] T011 [P] Create `KafkaMySQLProfile.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/` (inherits BaseBrokerTestProfile)
- [ ] T012 [P] Create `RabbitMQPostgresProfile.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/` (inherits BaseBrokerTestProfile)
- [ ] T013 [P] Create `RabbitMQMySQLProfile.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/profiles/` (inherits BaseBrokerTestProfile)

### Core Data Types

- [ ] T014 [P] Create `TestConfiguration.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/` with timeout, mocking config per contracts
- [ ] T015 [P] Create `LemlineEventTypes.kt` constants in `lemline-testing/src/main/kotlin/com/lemline/testing/` per cloud-event-apis.kt contract

### CloudEvent Dispatcher (Test Isolation)

- [ ] T016 Create `CloudEventDispatcher.kt` singleton in `lemline-testing/src/main/kotlin/com/lemline/testing/` per research.md section 4.5.1
- [ ] T017 Create `CloudEventDispatcherSubscriber.kt` messaging subscriber that calls dispatcher.dispatch() on events channel

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Run Complete Workflow Test Against Real Infrastructure (Priority: P1) 🎯 MVP

**Goal**: Execute workflows through real message brokers and databases with deterministic completion detection

**Independent Test**: Run a simple `set` task workflow through Kafka/PostgreSQL and verify it completes with expected output

### Core Entities for US1

- [ ] T018 [P] [US1] Create `CloudEventCapture.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/cloud-event-apis.kt
- [ ] T019 [P] [US1] Create `CloudEventCaptureImpl.kt` implementation with workflowId scoping and waiter notifications per research.md
- [ ] T020 [P] [US1] Create `WorkflowStateHooks.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/workflow-state-hooks.kt
- [ ] T021 [US1] Create `WorkflowStateHooksImpl.kt` wrapping CloudEventCapture with awaitCompletion, awaitTaskStarted methods
- [ ] T022 [P] [US1] Create `TestWorkflowExecutor.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/test-workflow-executor.kt
- [ ] T023 [US1] Create `TestWorkflowExecutorImpl.kt` implementation orchestrating workflow execution, registering with dispatcher

### Integration with lemline-runner

- [ ] T024 [US1] Add TestWorkflowExecutor CDI producer that injects runner components (CommandHandler, DefinitionRepository, etc.)
- [ ] T025 [US1] Implement workflow registration (insert into lemline_workflows table) in TestWorkflowExecutorImpl
- [ ] T026 [US1] Implement workflow start command emission to commands-in channel

### Validation

- [ ] T027 [US1] Create `SetTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` using KafkaPostgresProfile - verify simple set task
- [ ] T028 [US1] Create `InfrastructureSwitchingTest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - same test runs with all 4 profiles

**Checkpoint**: US1 complete - can execute simple workflows through real infrastructure with deterministic waiting

---

## Phase 4: User Story 2 - Control Activity Execution for Deterministic Tests (Priority: P2)

**Goal**: Mock HTTP, script, and shell activity responses for predictable test behavior

**Independent Test**: Run a workflow with HTTP call task, configure mock response, verify workflow receives mocked data

### Activity Mocking Entities

- [ ] T029 [P] [US2] Create `HttpResponse.kt`, `ScriptResponse.kt`, `ShellResponse.kt` data classes in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts
- [ ] T030 [P] [US2] Create `ActivityError.kt` data class for error simulation per contracts/test-activity-executor.kt
- [ ] T031 [P] [US2] Create `ActivityInvocation.kt` sealed class hierarchy (HttpInvocation, ScriptInvocation, ShellInvocation) per contracts
- [ ] T032 [P] [US2] Create `TestActivityExecutor.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/test-activity-executor.kt
- [ ] T033 [US2] Create `TestActivityExecutorImpl.kt` with response queues and invocation tracking

### Integration with ActivityRunner

- [ ] T034 [US2] Create `TestActivityRunner.kt` implementing ActivityRunner interface, delegating to TestActivityExecutor
- [ ] T035 [US2] Add TestConfiguration.withMocking {} builder DSL per data-model.md

### Validation

- [ ] T036 [US2] Create `HttpTaskMockingE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify HTTP mocking
- [ ] T037 [US2] Create `ScriptTaskMockingE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify script mocking
- [ ] T038 [US2] Create `ErrorSimulationE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify error injection

**Checkpoint**: US2 complete - can mock all activity types for deterministic testing

---

## Phase 5: User Story 3 - Verify Emitted CloudEvents (Priority: P2)

**Goal**: Capture and query lifecycle and custom CloudEvents emitted during workflow execution

**Independent Test**: Run workflow, verify WorkflowStarted, TaskCompleted, WorkflowCompleted events captured in order

### CloudEvent Query Methods

- [ ] T039 [US3] Implement CloudEventCapture.filterByType() returning filtered event list
- [ ] T040 [US3] Implement CloudEventCapture.filterBySource() returning filtered event list
- [ ] T041 [US3] Implement CloudEventCapture.lifecycleEvents() filtering com.lemline.* events
- [ ] T042 [US3] Implement CloudEventCapture.customEvents() filtering non-lifecycle events

### CloudEvent Delivery (for listen tasks)

- [ ] T043 [P] [US3] Create `CloudEventDelivery.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/cloud-event-apis.kt
- [ ] T044 [P] [US3] Create `CloudEventBuilder.kt` DSL interface per contracts/cloud-event-apis.kt
- [ ] T045 [US3] Create `CloudEventDeliveryImpl.kt` that publishes events to the messaging channel with optional workflowId targeting

### Validation

- [ ] T046 [US3] Create `LifecycleEventCaptureE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify lifecycle events captured
- [ ] T047 [US3] Create `EmitTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify custom emit events captured

**Checkpoint**: US3 complete - can capture and verify all CloudEvents from workflow execution

---

## Phase 6: User Story 4 - Reuse Existing Test Cases (Priority: P3)

**Goal**: Run WorkflowTestCase definitions from lemline-core against real infrastructure

**Independent Test**: Take a test case from lemline-core testFixtures, run through TestWorkflowExecutor, verify passes

### TestCase Compatibility

- [ ] T048 [US4] Implement TestWorkflowExecutor.execute(WorkflowTestCase, TestConfiguration) method per contracts
- [ ] T049 [US4] Add platform filtering support in TestConfiguration (unix-only, windows-only tags)
- [ ] T050 [US4] Add excludeTags filtering support per TestConfiguration contract

### Validation

- [ ] T051 [US4] Create `LemlineCoreCompatibilityE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` running subset of core test cases

**Checkpoint**: US4 complete - lemline-core test cases run through real infrastructure

---

## Phase 7: User Story 5 - Test Asynchronous Workflow Patterns (Priority: P3)

**Goal**: Test wait tasks, retry logic, and parent-child workflows with real outbox processing

**Independent Test**: Run workflow with 1-second wait, verify it pauses and resumes via outbox scheduler

### Async Pattern Support

- [ ] T052 [US5] Ensure outbox schedulers run in test profiles (wait outbox, retry outbox) per BaseBrokerTestProfile config
- [ ] T053 [US5] Implement WorkflowStateHooks.awaitTaskCompleted() for intermediate task verification
- [ ] T054 [US5] Create `WorkflowDependency.kt` data class in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts

### Parent-Child Workflow Support

- [ ] T055 [US5] Implement TestWorkflowExecutor.execute(yaml, input, dependencies) for child workflow registration
- [ ] T056 [US5] Add CloudEventCapture.addToScope(workflowId) for child workflow event capture

### Validation

- [ ] T057 [US5] Create `WaitTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify wait with outbox
- [ ] T058 [US5] Create `RetryE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify retry with backoff
- [ ] T059 [US5] Create `ParentChildWorkflowE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify run workflow task

**Checkpoint**: US5 complete - async patterns work with real outbox processing

---

## Phase 8: User Story 6 - Comprehensive Task Type Coverage (Priority: P2)

**Goal**: E2E tests for all 14 Lemline task types

**Independent Test**: Each task type has a minimal workflow test verifying correct behavior

### Control Flow Tasks

- [ ] T060 [P] [US6] Create `DoBlockE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - sequential execution
- [ ] T061 [P] [US6] Create `IfConditionE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - conditional branching
- [ ] T062 [P] [US6] Create `SwitchTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - multi-way branching
- [ ] T063 [P] [US6] Create `ForLoopE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - iteration

### Parallel Execution Tasks

- [ ] T064 [P] [US6] Create `ForkTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - parallel branches

### Event Tasks

- [ ] T065 [P] [US6] Create `ListenTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - one/any/all strategies
- [ ] T066 [P] [US6] Create `ListenTaskDeliveryE2ETest.kt` using CloudEventDelivery for deterministic event trigger

### Error Handling Tasks

- [ ] T067 [P] [US6] Create `TryCatchE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - error handling
- [ ] T068 [P] [US6] Create `RaiseTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - explicit errors

### External Call Tasks

- [ ] T069 [P] [US6] Create `ShellTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - with platform filtering

### Scheduling Tasks

- [ ] T070 [P] [US6] Create `ScheduledWorkflowE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify cron-triggered workflow (FR-030)

**Checkpoint**: US6 complete - all 14 task types have passing E2E tests

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T071 [P] Create framework self-test in `lemline-testing/src/test/kotlin/com/lemline/testing/SelfTest.kt`
- [ ] T072 [P] Update quickstart.md with verified working examples from E2E tests
- [ ] T073 Add KDoc documentation to all public APIs
- [ ] T074 [P] Migrate existing runner test infrastructure to use lemline-testing module
- [ ] T075 Run full test suite with all 4 infrastructure combinations (CI matrix)
- [ ] T076 Verify 99% test reliability over 10 consecutive runs (no flaky failures)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-8)**: All depend on Foundational phase completion
  - US1 (P1): Can proceed immediately after Foundational
  - US2, US3, US6 (P2): Can proceed in parallel after US1 or after Foundational
  - US4, US5 (P3): Can proceed in parallel, depend on core executor from US1
- **Polish (Phase 9)**: Depends on all user stories being complete

### User Story Dependencies

- **US1**: Foundation only - no other story dependencies (MVP)
- **US2**: Requires TestWorkflowExecutor from US1 to integrate mocking
- **US3**: Requires CloudEventCapture from US1, can start in parallel
- **US4**: Requires TestWorkflowExecutor.execute(WorkflowTestCase) from US1
- **US5**: Requires TestWorkflowExecutor, outbox scheduler config from US1
- **US6**: Requires all components, should be last before Polish

### Parallel Opportunities

**Within Phase 2 (Foundational)**:
```bash
# Launch all test resources in parallel:
Task T005: KafkaTestResource.kt
Task T006: RabbitMQTestResource.kt
Task T007: PostgresTestResource.kt
Task T008: MySQLTestResource.kt

# Launch all profiles in parallel (after T009):
Task T010: KafkaPostgresProfile.kt
Task T011: KafkaMySQLProfile.kt
Task T012: RabbitMQPostgresProfile.kt
Task T013: RabbitMQMySQLProfile.kt
```

**Within Phase 3 (US1)**:
```bash
# Launch capture and hooks interfaces in parallel:
Task T018: CloudEventCapture.kt interface
Task T020: WorkflowStateHooks.kt interface
Task T022: TestWorkflowExecutor.kt interface
```

**Within Phase 8 (US6)**:
```bash
# All E2E test files can be created in parallel:
Task T060-T069: All task type E2E tests (different files)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (module structure)
2. Complete Phase 2: Foundational (profiles, resources, dispatcher)
3. Complete Phase 3: User Story 1 (core executor)
4. **STOP and VALIDATE**: Run SetTaskE2ETest with KafkaPostgresProfile
5. Verify: Workflow executes through real Kafka, completes deterministically

### Incremental Delivery

1. **MVP**: Setup + Foundational + US1 → Can run basic workflows E2E
2. **+US2**: Add activity mocking → Deterministic HTTP/script tests
3. **+US3**: Add event capture → Verify CloudEvents
4. **+US4**: Add test case compat → Reuse lemline-core tests
5. **+US5**: Add async patterns → Wait, retry, child workflows
6. **+US6**: Add all task types → Complete DSL coverage
7. **Polish**: Docs, cleanup, CI matrix

### Suggested MVP Scope

Complete only:
- Phase 1: Setup (T001-T004)
- Phase 2: Foundational (T005-T017)
- Phase 3: User Story 1 (T018-T028)

This delivers:
- Working lemline-testing module
- 4 composable test profiles
- Core executor with deterministic completion
- Basic E2E test demonstrating the framework

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Tests use real LifecycleEventHookImpl CloudEvents (no test-only hooks)
- CloudEventDispatcher ensures test isolation via workflowId routing

## Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| 1 Setup | T001-T004 (4) | Module structure |
| 2 Foundational | T005-T017 (13) | Profiles, resources, dispatcher |
| 3 US1 (P1) | T018-T028 (11) | Core executor - MVP |
| 4 US2 (P2) | T029-T038 (10) | Activity mocking |
| 5 US3 (P2) | T039-T047 (9) | CloudEvent capture/delivery |
| 6 US4 (P3) | T048-T051 (4) | Test case compatibility |
| 7 US5 (P3) | T052-T059 (8) | Async patterns |
| 8 US6 (P2) | T060-T070 (11) | Task type coverage |
| 9 Polish | T071-T076 (6) | Docs, cleanup, CI |
| **Total** | **76 tasks** | |
