Questio# Feature Specification: End-to-End Testing Framework

**Feature Branch**: `001-testing-architecture`
**Created**: 2025-12-11
**Status**: Draft
**Input**: User description: "Build a testing framework using real infrastructure (in #runner). This should allow to
test workflow end-to-end using Kafka or RabbitMQ, and PostgreSQL or MySQL. Use a custom ActivityExecutor dedicated to
testing and check emitted CloudEvents."

## Clarifications

### Session 2025-12-11

- Q: Should the testing framework include comprehensive acceptance scenarios for ALL Lemline task types? → A: Yes, all
  task types must have explicit acceptance scenarios in the spec
- Q: Should the testing framework be a separate module or remain within lemline-runner? → A: Create new
  `lemline-testing` module (dedicated testing library)
- Q: How should test CloudEvents be delivered to trigger listen tasks? → A: Test explicitly sends CloudEvents at
  specific points during execution (programmatic delivery)
- Q: How should workflow test instances be isolated from each other? → A: Unique workflow IDs + names (both unique per
  test instance), enabling parallel test execution
- Q: How should Quarkus test profiles be organized for infrastructure switching? → A: Composable profiles - separate
  broker + database profiles combined at runtime for maximum flexibility
- Q: How should the testing framework ensure determinism for asynchronous workflow operations? → A: Event-based
  synchronization - wait for specific workflow state transitions via callbacks (no arbitrary delays)
- Q: How should test data be cleaned up between test runs? → A: No explicit cleanup needed - unique workflow IDs and
  names per test provide sufficient isolation
- Q: Should the testing framework use a dedicated test-specific LifecycleEventHook for event synchronization? → A: No,
  use the default `LifecycleEventHookImpl` which emits real CloudEvents to the messaging channel.
  `CloudEventCapture` subscribes to capture these events, and `WorkflowStateHooks` provides convenient `await*` methods
  that wait for specific CloudEvents. This approach: (1) tests verify the real event emission path, (2) no test-specific
  hooks to maintain, (3) tests see exactly what production users see.
- Q: How should the test workbench architecture be structured? → A: Native binary orchestration - tests spawn the
  native-compiled Lemline runner as a separate process and interact via CloudEvents through the message broker. An
  external verification process reads/emits CloudEvents to check and interact with test workflows. This provides true
  production-like testing with process isolation.
- Q: How should activity mocking be implemented for testing? → A: CLI test mode built into runner. The runner includes
  a `TestActivityExecutor` activated via `--test-mode` CLI flag, with mock responses configured via `--mock-config`
  file. Same binary for production and test - just different execution mode. The `lemline-testing` module spawns the
  runner with these flags and provides CloudEvent capture (to verify workflow behavior) and CloudEvent delivery (to
  trigger listen tasks).
- Q: Who provisions test infrastructure (broker/database containers)? → A: `lemline-testing` manages infrastructure.
  Testcontainers are started by `TestWorkflowExecutor`, keeping test code simple. Tests just call
  `TestWorkflowExecutor.start()` and infrastructure is ready.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run Complete Workflow Test Against Real Infrastructure (Priority: P1)

As a Lemline developer, I want to run end-to-end tests that execute workflows through real message brokers and
databases, so that I can verify the entire system works correctly in production-like conditions.

**Why this priority**: This is the core value proposition of the testing framework. Without this capability, developers
cannot confidently validate that workflows behave correctly with real infrastructure.

**Independent Test**: Can be fully tested by running a simple workflow (e.g., set task with input/output) through a real
Kafka/PostgreSQL stack and verifying the workflow completes successfully with expected output.

**Acceptance Scenarios**:

1. **Given** a workflow definition and a configured test environment with Kafka and PostgreSQL, **When** I execute the
   workflow test, **Then** messages flow through the real Kafka broker and state is persisted to PostgreSQL, and the
   test completes with the expected workflow output.

2. **Given** a workflow definition with all supported task types (set, call http, run script, run shell, run workflow,
   wait, for, fork, listen, emit, raise, try, switch, do), **When** I run the end-to-end test suite, **Then** all task
   types execute correctly through the real messaging infrastructure.

3. **Given** the same test case, **When** I run it with different broker configurations (Kafka vs RabbitMQ), **Then**
   the test passes with both brokers without code changes.

4. **Given** the same test case, **When** I run it with different database configurations (PostgreSQL vs MySQL), **Then
   ** the test passes with both databases without code changes.

---

### User Story 2 - Control Activity Execution for Deterministic Tests (Priority: P2)

As a Lemline developer, I want to use a test-specific activity executor that allows me to control external call
responses, so that I can write deterministic tests without depending on external services.

**Why this priority**: Many workflow tests involve HTTP calls or external integrations. Without controllable activity
execution, tests become flaky and dependent on external service availability.

**Independent Test**: Can be tested by running a workflow with an HTTP task and configuring the test activity executor
to return a predetermined response, then verifying the workflow processes that response correctly.

**Acceptance Scenarios**:

1. **Given** a workflow with an HTTP call task and a test-configured response, **When** the workflow executes the HTTP
   task, **Then** the test activity executor returns the configured response instead of making a real HTTP call.

2. **Given** a workflow with multiple HTTP calls, **When** I configure different responses for each call, **Then** each
   call receives its configured response in sequence.

3. **Given** a workflow test that should simulate an error, **When** I configure the test activity executor to return an
   error, **Then** the workflow handles the error according to its error handling definition.

---

### User Story 3 - Verify Emitted CloudEvents (Priority: P2)

As a Lemline developer, I want to capture and verify all CloudEvents emitted during workflow execution, so that I can
test workflow lifecycle events and integrations that depend on these events.

**Why this priority**: CloudEvents are the primary integration mechanism for external systems. Verifying correct event
emission ensures integrations work properly.

**Independent Test**: Can be tested by running a workflow and asserting that specific CloudEvents (e.g.,
WorkflowStarted, TaskCompleted, WorkflowCompleted) were emitted with correct attributes.

**Acceptance Scenarios**:

1. **Given** a workflow test execution, **When** the workflow completes, **Then** I can access a list of all emitted
   CloudEvents in order.

2. **Given** a workflow that emits lifecycle events, **When** I query the captured events, **Then** I can filter by
   event type and verify event attributes (source, type, data).

3. **Given** a workflow that uses emit tasks to publish custom CloudEvents, **When** the workflow completes, **Then**
   those custom events are captured alongside lifecycle events with correct type, source, and data attributes.

---

### User Story 4 - Reuse Existing Test Cases (Priority: P3)

As a Lemline developer, I want to reuse test cases from lemline-core's testFixtures against real infrastructure, so that
I can ensure the runner behaves consistently with the core execution engine.

**Why this priority**: Consistency between core and runner execution is essential but builds on top of the basic testing
capability.

**Independent Test**: Can be tested by taking a single WorkflowTestCase from lemline-core and running it through the
end-to-end framework, verifying the same expected output.

**Acceptance Scenarios**:

1. **Given** a WorkflowTestCase from lemline-core, **When** I run it through the end-to-end test executor, **Then** the
   test passes with the same validation criteria.

2. **Given** a set of test cases with tags (e.g., "unix-only", "windows-only"), **When** I run the test suite, **Then**
   tests are filtered based on the current platform.

---

### User Story 5 - Test Asynchronous Workflow Patterns (Priority: P3)

As a Lemline developer, I want to test workflows that use wait tasks, retry logic, and child workflows, so that I can
verify complex asynchronous patterns work correctly with real outbox processing.

**Why this priority**: Asynchronous patterns require outbox schedulers and database persistence, making them dependent
on the infrastructure testing capability.

**Independent Test**: Can be tested by running a workflow with a short wait duration and verifying it resumes correctly
after the outbox scheduler processes it.

**Acceptance Scenarios**:

1. **Given** a workflow with a wait task, **When** the test runs with outbox schedulers enabled, **Then** the workflow
   pauses, the wait is persisted to the database, and the workflow resumes after the specified duration.

2. **Given** a workflow with retry logic on a failing task, **When** the task fails, **Then** retries are scheduled via
   the outbox and executed with configured backoff.

3. **Given** a parent workflow that calls a child workflow (run workflow task), **When** the test runs, **Then** the
   parent waits for the child to complete and receives the child's output.

---

### User Story 6 - Comprehensive Task Type Coverage (Priority: P2)

As a Lemline developer, I want acceptance scenarios for every Lemline task type, so that I can verify complete DSL
coverage and prevent regressions across all workflow capabilities.

**Why this priority**: Complete task coverage ensures no workflow feature is left untested. Missing coverage could lead
to production bugs in less-commonly-used task types.

**Independent Test**: Each task type scenario can be tested independently by running a minimal workflow that exercises
that specific task.

**Acceptance Scenarios - Data & Variables**:

1. **Given** a workflow with a `set` task that assigns variables, **When** the workflow executes, **Then** the variables
   are correctly set and available to subsequent tasks.

2. **Given** a workflow with `export` context directives, **When** tasks export data to workflow context, **Then** the
   exported values are accessible in subsequent tasks and the final output.

**Acceptance Scenarios - Control Flow**:

3. **Given** a workflow with a `do` block containing sequential tasks, **When** the workflow executes, **Then** tasks
   execute in order and each task receives the output of the previous task.

4. **Given** a workflow with an `if` condition, **When** the condition evaluates to true/false, **Then** the appropriate
   branch executes.

5. **Given** a workflow with a `switch` task with multiple cases, **When** the input matches a case, **Then** the
   corresponding branch executes; when no case matches and a default exists, **Then** the default branch executes.

6. **Given** a workflow with a `for` loop iterating over a collection, **When** the workflow executes, **Then** the loop
   body executes once per item with correct item context.

**Acceptance Scenarios - Parallel Execution**:

7. **Given** a workflow with a `fork` task defining parallel branches, **When** the workflow executes, **Then** all
   branches execute concurrently and the workflow waits for all branches to complete before continuing.

8. **Given** a fork task where one branch fails, **When** the failure occurs, **Then** the fork task behavior follows
   the configured competition mode (wait all vs first).

**Acceptance Scenarios - External Calls**:

9. **Given** a workflow with a `call http` task, **When** the workflow executes with a mocked response, **Then** the
   HTTP call is intercepted and the configured response is returned.

10. **Given** a workflow with a `run script` task (JavaScript/Python), **When** the workflow executes, **Then** the
    script runs and its output is captured.

11. **Given** a workflow with a `run shell` task, **When** the workflow executes, **Then** the shell command runs and
    its output is captured (platform-specific tests filtered appropriately).

**Acceptance Scenarios - Event-Driven**:

12. **Given** a workflow with a `listen` task waiting for a CloudEvent, **When** the matching CloudEvent is delivered, *
    *Then** the workflow resumes with the event data.

13. **Given** a workflow with a `listen` task using `all` strategy (multiple events required), **When** all required
    events are delivered, **Then** the workflow resumes with all event data.

14. **Given** a workflow with a `listen` task using `any` strategy with `until` condition, **When** events arrive until
    the condition is met, **Then** the workflow resumes with accumulated event data.

15. **Given** a workflow with an `emit` task, **When** the workflow executes, **Then** a CloudEvent is published with
    the configured type, source, and data.

**Acceptance Scenarios - Error Handling**:

16. **Given** a workflow with a `try` block and `catch` handler, **When** an error occurs in the try block, **Then** the
    catch handler executes with error context.

17. **Given** a workflow with a `raise` task, **When** the raise task executes, **Then** the workflow raises an error
    with the configured error type and message.

18. **Given** a workflow with retry configuration on a task, **When** the task fails transiently, **Then** the task is
    retried according to the backoff policy.

**Acceptance Scenarios - Scheduling & Orchestration**:

19. **Given** a workflow scheduled via cron expression, **When** the schedule triggers, **Then** a new workflow instance
    starts automatically.

20. **Given** a workflow with a `run workflow` task invoking a child workflow, **When** the child workflow
    completes/fails, **Then** the parent workflow receives the result and continues/handles the error appropriately.

---

### Edge Cases

- What happens when the test workflow exceeds the configured timeout?
- How does the system handle broker connection failures during test setup?
- What happens when a test attempts to verify events that were not emitted?
- How are concurrent workflow instances isolated in tests?
- What happens when a listen task times out waiting for events?
- How does fork handle partial branch failures with different competition modes?
- What happens when a scheduled workflow trigger fires during test execution?

## Requirements *(mandatory)*

### Functional Requirements

#### Infrastructure Support

- **FR-001**: System MUST support running workflow tests against Kafka as a message broker
- **FR-002**: System MUST support running workflow tests against RabbitMQ as a message broker
- **FR-003**: System MUST support running workflow tests against PostgreSQL as a database
- **FR-004**: System MUST support running workflow tests against MySQL as a database
- **FR-035**: System MUST execute workflows by spawning the native-compiled Lemline runner as a separate process (native
  binary orchestration)
- **FR-036**: System MUST interact with workflow tests exclusively via CloudEvents through the message broker (no
  in-process coupling)
- **FR-037**: Runner MUST provide `--test-mode` CLI flag that activates `TestActivityExecutor` for mock responses
- **FR-038**: Runner MUST provide `--mock-config=<path>` CLI option to load activity mock responses from YAML/JSON file

#### Test Execution Control

- **FR-005**: System MUST provide a test-specific activity executor that allows configuring predetermined responses for
  HTTP calls
- **FR-006**: System MUST provide a test-specific activity executor that allows configuring predetermined responses for
  script/shell execution
- **FR-007**: System MUST support configurable test timeouts with clear error messages on timeout
- **FR-008**: System MUST isolate test workflow instances using unique workflow IDs and names per test, enabling
  parallel test execution without cross-test interference; no explicit data cleanup required between tests

#### Determinism & Anti-Flakiness (Quarkus Best Practices)

- **FR-031**: System MUST use event-based synchronization for async operations - tests wait for specific workflow state
  transitions via callbacks, not arbitrary delays
- **FR-032**: System MUST provide workflow state transition hooks (e.g., `onTaskCompleted`, `onWorkflowCompleted`,
  `onListenStarted`) for deterministic test assertions
- **FR-033**: System MUST ensure tests produce identical results on repeated runs with same inputs (deterministic
  execution)
- **FR-034**: System MUST NOT use `Thread.sleep()` or fixed delays for synchronization in test infrastructure

#### CloudEvent Verification

- **FR-009**: System MUST capture all CloudEvents emitted during workflow execution (lifecycle and custom)
- **FR-010**: System MUST allow querying captured CloudEvents by type, source, or custom attributes
- **FR-011**: System MUST support programmatic delivery of test CloudEvents at specific points during test execution to
  trigger listen tasks

#### Test Case Compatibility

- **FR-012**: System MUST support running existing WorkflowTestCase definitions from lemline-core
- **FR-013**: System MUST provide clear error messages when workflow execution fails

#### Task Type Support (Comprehensive)

- **FR-014**: System MUST support testing `set` tasks (variable assignment)
- **FR-015**: System MUST support testing `do` blocks (sequential execution)
- **FR-016**: System MUST support testing `if` conditions (conditional branching)
- **FR-017**: System MUST support testing `switch` tasks (multi-way branching)
- **FR-018**: System MUST support testing `for` loops (iteration)
- **FR-019**: System MUST support testing `fork` tasks (parallel execution)
- **FR-020**: System MUST support testing `wait` tasks with outbox processing
- **FR-021**: System MUST support testing `listen` tasks (CloudEvent consumption) with all strategies (one, any, all)
- **FR-022**: System MUST support testing `emit` tasks (CloudEvent production)
- **FR-023**: System MUST support testing `call http` tasks with mocked responses
- **FR-024**: System MUST support testing `run script` tasks (JavaScript, Python)
- **FR-025**: System MUST support testing `run shell` tasks with platform filtering
- **FR-026**: System MUST support testing `run workflow` tasks (child workflow invocation)
- **FR-027**: System MUST support testing `try`/`catch` error handling
- **FR-028**: System MUST support testing `raise` tasks (explicit errors)
- **FR-029**: System MUST support testing retry logic with backoff scheduling
- **FR-030**: System MUST support testing scheduled workflow triggers

### Key Entities

**In `lemline-runner` (CLI test mode)**:

- **TestActivityExecutor**: Built into the runner, activated via `--test-mode` CLI flag. Intercepts activity calls
  (HTTP, script, shell) and returns mock responses from `--mock-config` file. Implements the existing `ActivityExecutor`
  interface.

**In `lemline-testing` (test harness module)**:

- **TestWorkflowExecutor**: Orchestrates full test lifecycle - starts Testcontainers (broker + database), spawns
  native-compiled Lemline runner with `--test-mode` flag, manages runner lifecycle (start/stop), generates mock
  configuration files, triggers workflow instances, handles teardown
- **CloudEventCapture**: Subscribes to the message broker to capture all CloudEvents emitted during workflow execution;
  provides query interface to verify workflow behavior (filter by type, source, workflow ID)
- **CloudEventDelivery**: Publishes CloudEvents to the message broker to trigger `listen` tasks in running workflows;
  enables programmatic event delivery at specific test points
- **WorkflowStateHooks**: Built on CloudEventCapture - provides `await*` methods (e.g., `awaitWorkflowCompleted`,
  `awaitTaskStarted`) that wait for specific lifecycle CloudEvents, enabling deterministic test synchronization
- **TestConfiguration**: Defines broker connection, database connection, runner binary path, timeouts, and generates
  mock config files for the runner

### Module Structure

Testing capability is split between two modules:

**`lemline-runner`** (extended with CLI test mode):
- `TestActivityExecutor`: Implements `ActivityExecutor` interface, returns mock responses
- Activated via `--test-mode` CLI flag
- Mock responses loaded from `--mock-config=<path>` YAML/JSON file
- Same production binary - no separate test build required

**`lemline-testing`** (new test harness module):
- Depends on: `lemline-core` (workflow definitions), CloudEvents SDK, Kafka/RabbitMQ client libraries, Testcontainers
- Does NOT depend on `lemline-runner` (runner is spawned as external native binary)
- Provides:
    - `TestWorkflowExecutor`: Manages full test lifecycle - starts Testcontainers (broker + database), spawns runner
      with `--test-mode`, handles teardown
    - `CloudEventCapture`: Subscribe to broker, verify workflow behavior
    - `CloudEventDelivery`: Publish events to trigger `listen` tasks
    - `WorkflowStateHooks`: Deterministic await utilities
- Consumers: `lemline-runner` E2E tests, external projects testing Lemline workflows

### Test Infrastructure Architecture

With native binary orchestration, `TestWorkflowExecutor` manages the full test lifecycle:

1. **Start Infrastructure**: Testcontainers start Kafka/RabbitMQ and PostgreSQL/MySQL containers
2. **Generate Configuration**: Create runner config file pointing to container ports + mock responses
3. **Spawn Runner**: Start native binary with `--test-mode --mock-config=<path>` and infrastructure config
4. **Connect to Broker**: `CloudEventCapture` and `CloudEventDelivery` connect to broker for event interaction
5. **Execute Tests**: Tests trigger workflows, emit events, await completions, verify behavior
6. **Teardown**: Stop runner process, stop containers

**Composable Configurations**: `KafkaConfig`, `RabbitMQConfig`, `PostgresConfig`, `MySQLConfig` classes generate
appropriate runner configuration for each infrastructure combination. Same test class runs with different configurations
to validate all 4 combinations without code duplication.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Developers can run a complete workflow test from definition to completion in under 30 seconds (excluding
  infrastructure startup)
- **SC-002**: Test failures provide clear diagnostics including workflow position, state, and relevant error messages
- **SC-003**: 100% of existing lemline-core test cases can be executed through the end-to-end framework
- **SC-004**: Tests run reliably with 99% success rate when infrastructure is healthy (no flaky failures)
- **SC-005**: Adding a new test case requires only defining the workflow YAML and expected outcome (no infrastructure
  boilerplate)
- **SC-006**: All four infrastructure combinations (Kafka/RabbitMQ x PostgreSQL/MySQL) pass the same test suite
- **SC-007**: All 14 Lemline task types have passing end-to-end tests validating correct behavior
