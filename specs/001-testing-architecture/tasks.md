# Tasks: End-to-End Testing Framework

**Input**: Design documents from `/specs/001-testing-architecture/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Runner module**: `lemline-runner/src/main/kotlin/com/lemline/runner/`
- **Runner tests**: `lemline-runner/src/test/kotlin/com/lemline/runner/`
- **Testing module**: `lemline-testing/src/main/kotlin/com/lemline/testing/`
- **Testing infrastructure**: `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/`
- **E2E tests**: `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/`

---

## Phase 1: Setup (Module Infrastructure)

**Purpose**: Create the lemline-testing module and extend lemline-runner with test mode

- [ ] T001 Create `lemline-testing` directory structure per plan.md
- [ ] T002 Create `lemline-testing/build.gradle.kts` with dependencies (Testcontainers, Kotest, Kafka/RabbitMQ clients, CloudEvents SDK) - NO Quarkus dependency
- [ ] T003 Add `lemline-testing` to `settings.gradle.kts` include list
- [ ] T004 [P] Create package directories: `com.lemline.testing`, `com.lemline.testing.infrastructure`

---

## Phase 2: Foundational (Runner CLI Test Mode)

**Purpose**: Extend lemline-runner with `--test-mode` CLI flag and TestActivityExecutor - BLOCKS all user stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete (native binary must support test mode)

### CLI Flags (FR-037, FR-038)

- [ ] T005 Add `--test-mode` CLI flag to `ListenCommand.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/cli/ListenCommand.kt`
- [ ] T006 Add `--mock-config=<path>` CLI option to `ListenCommand.kt` for loading mock configuration file

### TestActivityExecutor (in runner)

- [ ] T007 [P] Create `MockConfiguration.kt` data class in `lemline-runner/src/main/kotlin/com/lemline/runner/activities/` with HttpMockRule, ScriptMockRule, ShellMockRule
- [ ] T008 [P] Create `MockConfigurationParser.kt` in `lemline-runner/src/main/kotlin/com/lemline/runner/activities/` for YAML/JSON parsing using kaml
- [ ] T009 Create `TestActivityExecutor.kt` implementing `ActivityExecutor` interface in `lemline-runner/src/main/kotlin/com/lemline/runner/activities/`
- [ ] T010 Wire CDI to select `TestActivityExecutor` when `--test-mode` flag is active in runner

### Unit Tests for Test Mode

- [ ] T011 [P] Create `MockConfigurationParserTest.kt` in `lemline-runner/src/test/kotlin/com/lemline/runner/activities/` - test YAML parsing
- [ ] T012 [P] Create `TestActivityExecutorTest.kt` in `lemline-runner/src/test/kotlin/com/lemline/runner/activities/` - test mock matching

**Checkpoint**: Runner can start with `--test-mode --mock-config=<path>` and return mock responses

---

## Phase 3: User Story 1 - Run Complete Workflow Test Against Real Infrastructure (Priority: P1) 🎯 MVP

**Goal**: Execute workflows through real message brokers and databases by spawning native runner binary

**Independent Test**: Start Testcontainers, spawn native runner with `--test-mode`, run a simple `set` task workflow through Kafka/PostgreSQL, verify it completes with expected output

### Infrastructure Containers

- [ ] T013 [P] [US1] Create `KafkaContainer.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/` using Testcontainers
- [ ] T014 [P] [US1] Create `RabbitMQContainer.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/` using Testcontainers
- [ ] T015 [P] [US1] Create `PostgresContainer.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/` using Testcontainers
- [ ] T016 [P] [US1] Create `MySQLContainer.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/` using Testcontainers
- [ ] T017 [P] [US1] Create `BrokerType.kt` enum (KAFKA, RABBITMQ) in `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/`
- [ ] T018 [P] [US1] Create `DatabaseType.kt` enum (POSTGRESQL, MYSQL) in `lemline-testing/src/main/kotlin/com/lemline/testing/infrastructure/`

### Runner Process Management

- [ ] T019 [P] [US1] Create `RunnerProcess.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/` for spawning/managing native binary process
- [ ] T020 [P] [US1] Create `RunnerConfigGenerator.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/` for generating runner config files pointing to container ports

### Core Test Entities

- [ ] T021 [P] [US1] Create `TestConfiguration.kt` in `lemline-testing/src/main/kotlin/com/lemline/testing/` with broker type, database type, timeouts, runner binary path
- [ ] T022 [P] [US1] Create `MockConfig.kt` DSL builder in `lemline-testing/src/main/kotlin/com/lemline/testing/` for generating mock config files
- [ ] T023 [P] [US1] Create `WorkflowResult.kt` data class in `lemline-testing/src/main/kotlin/com/lemline/testing/` (status, output, error)
- [ ] T024 [P] [US1] Create `LemlineEventTypes.kt` constants in `lemline-testing/src/main/kotlin/com/lemline/testing/` per cloud-event-apis.kt contract

### CloudEvent Infrastructure

- [ ] T025 [P] [US1] Create `CloudEventCapture.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/cloud-event-apis.kt
- [ ] T026 [US1] Create `KafkaCloudEventCapture.kt` implementation subscribing to Kafka broker in `lemline-testing/src/main/kotlin/com/lemline/testing/`
- [ ] T027 [US1] Create `RabbitMQCloudEventCapture.kt` implementation subscribing to RabbitMQ broker in `lemline-testing/src/main/kotlin/com/lemline/testing/`
- [ ] T028 [P] [US1] Create `CloudEventDispatcher.kt` singleton in `lemline-testing/src/main/kotlin/com/lemline/testing/` for routing events by workflowId

### WorkflowStateHooks (Determinism)

- [ ] T029 [P] [US1] Create `WorkflowStateHooks.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/workflow-state-hooks.kt
- [ ] T030 [US1] Create `WorkflowStateHooksImpl.kt` wrapping CloudEventCapture with `awaitCompletion`, `awaitTaskStarted` methods

### TestWorkflowExecutor

- [ ] T031 [P] [US1] Create `TestWorkflowExecutor.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/test-workflow-executor.kt
- [ ] T032 [US1] Create `TestWorkflowExecutorImpl.kt` orchestrating: Testcontainers lifecycle, runner spawning, workflow execution via CLI

### CLI Integration

- [ ] T033 [US1] Implement `defineWorkflow(yaml)` in TestWorkflowExecutor - calls runner CLI `definition create`
- [ ] T034 [US1] Implement `runWorkflow(name, version, input)` in TestWorkflowExecutor - calls runner CLI `instance start`
- [ ] T035 [US1] Implement `startWorkflowAsync(name, version, input)` in TestWorkflowExecutor - non-blocking workflow start

### Validation

- [ ] T036 [US1] Create `SetTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify simple set task with Kafka + PostgreSQL
- [ ] T037 [US1] Create `InfrastructureSwitchingTest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - same test runs with all 4 broker/database combinations

**Checkpoint**: US1 complete - can execute simple workflows through real infrastructure with deterministic waiting

---

## Phase 4: User Story 2 - Control Activity Execution for Deterministic Tests (Priority: P2)

**Goal**: Configure mock responses for HTTP, script, and shell activities via mock config file

**Independent Test**: Run a workflow with HTTP call task, provide mock config, verify workflow receives mocked data

### Mock Config DSL Enhancement

- [ ] T038 [P] [US2] Add HTTP mock builder DSL to `MockConfig.kt` with match/respond pattern
- [ ] T039 [P] [US2] Add Script mock builder DSL to `MockConfig.kt` with language matching
- [ ] T040 [P] [US2] Add Shell mock builder DSL to `MockConfig.kt` with command pattern matching
- [ ] T041 [US2] Implement `MockConfig.toYaml()` for generating mock config file

### Validation

- [ ] T042 [US2] Create `HttpTaskMockingE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify HTTP mocking
- [ ] T043 [US2] Create `ScriptTaskMockingE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify script mocking
- [ ] T044 [US2] Create `ErrorSimulationE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify error injection via mock config

**Checkpoint**: US2 complete - can mock all activity types for deterministic testing

---

## Phase 5: User Story 3 - Verify Emitted CloudEvents (Priority: P2)

**Goal**: Capture and query lifecycle and custom CloudEvents emitted during workflow execution

**Independent Test**: Run workflow, verify WorkflowStarted, TaskCompleted, WorkflowCompleted events captured in order

### CloudEvent Query Methods

- [ ] T045 [US3] Implement `CloudEventCapture.filterByType()` returning filtered event list
- [ ] T046 [US3] Implement `CloudEventCapture.filterBySource()` returning filtered event list
- [ ] T047 [US3] Implement `CloudEventCapture.lifecycleEvents()` filtering `com.lemline.*` events
- [ ] T048 [US3] Implement `CloudEventCapture.customEvents()` filtering non-lifecycle events

### CloudEvent Delivery (for listen tasks)

- [ ] T049 [P] [US3] Create `CloudEventDelivery.kt` interface in `lemline-testing/src/main/kotlin/com/lemline/testing/` per contracts/cloud-event-apis.kt
- [ ] T050 [P] [US3] Create `CloudEventBuilder.kt` DSL interface in `lemline-testing/src/main/kotlin/com/lemline/testing/`
- [ ] T051 [US3] Create `KafkaCloudEventDelivery.kt` implementation publishing to Kafka broker
- [ ] T052 [US3] Create `RabbitMQCloudEventDelivery.kt` implementation publishing to RabbitMQ broker

### Validation

- [ ] T053 [US3] Create `LifecycleEventCaptureE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify lifecycle events captured
- [ ] T054 [US3] Create `EmitTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify custom emit events captured

**Checkpoint**: US3 complete - can capture and verify all CloudEvents from workflow execution

---

## Phase 6: User Story 4 - Reuse Existing Test Cases (Priority: P3)

**Goal**: Run WorkflowTestCase definitions from lemline-core against real infrastructure

**Independent Test**: Take a test case from lemline-core testFixtures, run through TestWorkflowExecutor, verify passes

### TestCase Compatibility

- [ ] T055 [US4] Implement `TestWorkflowExecutor.execute(WorkflowTestCase, TestConfiguration)` method
- [ ] T056 [US4] Add platform filtering support in TestConfiguration (unix-only, windows-only tags)
- [ ] T057 [US4] Add excludeTags filtering support per TestConfiguration contract

### Validation

- [ ] T058 [US4] Create `LemlineCoreCompatibilityE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` running subset of core test cases

**Checkpoint**: US4 complete - lemline-core test cases run through real infrastructure

---

## Phase 7: User Story 5 - Test Asynchronous Workflow Patterns (Priority: P3)

**Goal**: Test wait tasks, retry logic, and parent-child workflows with real outbox processing

**Independent Test**: Run workflow with 1-second wait, verify it pauses and resumes via outbox scheduler

### Async Pattern Support

- [ ] T059 [US5] Implement `WorkflowStateHooks.awaitTaskCompleted()` for intermediate task verification
- [ ] T060 [US5] Create `WorkflowDependency.kt` data class in `lemline-testing/src/main/kotlin/com/lemline/testing/`

### Parent-Child Workflow Support

- [ ] T061 [US5] Implement `TestWorkflowExecutor.runWorkflow(yaml, input, dependencies)` for registering child workflows
- [ ] T062 [US5] Implement `CloudEventCapture.addToScope(workflowId)` for child workflow event capture

### Validation

- [ ] T063 [US5] Create `WaitTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify wait with outbox
- [ ] T064 [US5] Create `RetryE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify retry with backoff
- [ ] T065 [US5] Create `ParentChildWorkflowE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify run workflow task

**Checkpoint**: US5 complete - async patterns work with real outbox processing

---

## Phase 8: User Story 6 - Comprehensive Task Type Coverage (Priority: P2)

**Goal**: E2E tests for all 14 Lemline task types

**Independent Test**: Each task type has a minimal workflow test verifying correct behavior

### Control Flow Tasks

- [ ] T066 [P] [US6] Create `DoBlockE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - sequential execution
- [ ] T067 [P] [US6] Create `IfConditionE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - conditional branching
- [ ] T068 [P] [US6] Create `SwitchTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - multi-way branching
- [ ] T069 [P] [US6] Create `ForLoopE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - iteration

### Parallel Execution Tasks

- [ ] T070 [P] [US6] Create `ForkTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - parallel branches

### Event Tasks

- [ ] T071 [P] [US6] Create `ListenTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - one/any/all strategies
- [ ] T072 [P] [US6] Create `ListenTaskDeliveryE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - using CloudEventDelivery for deterministic event trigger

### Error Handling Tasks

- [ ] T073 [P] [US6] Create `TryCatchE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - error handling
- [ ] T074 [P] [US6] Create `RaiseTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - explicit errors

### External Call Tasks

- [ ] T075 [P] [US6] Create `ShellTaskE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - with platform filtering

### Scheduling Tasks

- [ ] T076 [P] [US6] Create `ScheduledWorkflowE2ETest.kt` in `lemline-testing/src/test/kotlin/com/lemline/testing/e2e/` - verify cron-triggered workflow (FR-030)

**Checkpoint**: US6 complete - all 14 task types have passing E2E tests

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T077 [P] Create framework self-test in `lemline-testing/src/test/kotlin/com/lemline/testing/SelfTest.kt`
- [ ] T078 [P] Update quickstart.md with verified working examples from E2E tests
- [ ] T079 Add KDoc documentation to all public APIs in lemline-testing
- [ ] T080 Run full test suite with all 4 infrastructure combinations (CI matrix)
- [ ] T081 Verify 99% test reliability over 10 consecutive runs (no flaky failures)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories (runner must support test mode)
- **User Stories (Phase 3-8)**: All depend on Foundational phase completion
  - US1 (P1): Can proceed immediately after Foundational
  - US2, US3, US6 (P2): Can proceed in parallel after US1 or after Foundational
  - US4, US5 (P3): Can proceed in parallel, depend on core executor from US1
- **Polish (Phase 9)**: Depends on all user stories being complete

### User Story Dependencies

- **US1**: Foundation only - no other story dependencies (MVP)
- **US2**: Requires MockConfig from US1 to extend DSL
- **US3**: Requires CloudEventCapture from US1, can start in parallel
- **US4**: Requires TestWorkflowExecutor.execute(WorkflowTestCase) from US1
- **US5**: Requires TestWorkflowExecutor, WorkflowStateHooks from US1
- **US6**: Requires all components, should be last before Polish

### Parallel Opportunities

**Within Phase 2 (Foundational)**:
```
# These can run in parallel:
Task T007: MockConfiguration.kt
Task T008: MockConfigurationParser.kt
Task T011: MockConfigurationParserTest.kt
Task T012: TestActivityExecutorTest.kt
```

**Within Phase 3 (US1)**:
```
# All container implementations in parallel:
Task T013-T018: All infrastructure containers and enums

# All core entity interfaces in parallel:
Task T021-T025: TestConfiguration, MockConfig, WorkflowResult, LemlineEventTypes, CloudEventCapture interface
```

**Within Phase 8 (US6)**:
```
# All E2E test files can be created in parallel:
Task T066-T076: All task type E2E tests (different files)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (module structure)
2. Complete Phase 2: Foundational (runner test mode with CLI flags)
3. Complete Phase 3: User Story 1 (core executor with native binary spawning)
4. **STOP and VALIDATE**: Run SetTaskE2ETest with Kafka + PostgreSQL
5. Verify: Native runner spawns, workflow executes, completes deterministically

### Incremental Delivery

1. **MVP**: Setup + Foundational + US1 → Can run basic workflows E2E
2. **+US2**: Add activity mocking DSL → Deterministic HTTP/script tests
3. **+US3**: Add event capture/delivery → Verify CloudEvents, trigger listen tasks
4. **+US4**: Add test case compat → Reuse lemline-core tests
5. **+US5**: Add async patterns → Wait, retry, child workflows
6. **+US6**: Add all task types → Complete DSL coverage
7. **Polish**: Docs, cleanup, CI matrix

### Suggested MVP Scope

Complete only:
- Phase 1: Setup (T001-T004)
- Phase 2: Foundational (T005-T012)
- Phase 3: User Story 1 (T013-T037)

This delivers:
- Native runner with `--test-mode` CLI flag
- Working lemline-testing module
- Testcontainers infrastructure (4 combinations)
- Core executor spawning native binary
- Basic E2E test demonstrating the framework

### CI Pipeline Order

1. **Stage 1**: Build `lemline-runner` native binary
2. **Stage 2**: Run `lemline-runner` unit tests (TestActivityExecutor)
3. **Stage 3**: Run `lemline-testing` E2E tests (spawns native binary)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Native binary must be built BEFORE E2E tests can run
- Tests use real LifecycleEventHookImpl CloudEvents (no test-only hooks)
- CloudEventDispatcher ensures test isolation via workflowId routing

## Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| 1 Setup | T001-T004 (4) | Module structure |
| 2 Foundational | T005-T012 (8) | Runner CLI test mode |
| 3 US1 (P1) | T013-T037 (25) | Core executor - MVP |
| 4 US2 (P2) | T038-T044 (7) | Activity mocking DSL |
| 5 US3 (P2) | T045-T054 (10) | CloudEvent capture/delivery |
| 6 US4 (P3) | T055-T058 (4) | Test case compatibility |
| 7 US5 (P3) | T059-T065 (7) | Async patterns |
| 8 US6 (P2) | T066-T076 (11) | Task type coverage |
| 9 Polish | T077-T081 (5) | Docs, cleanup, CI |
| **Total** | **81 tasks** | |
