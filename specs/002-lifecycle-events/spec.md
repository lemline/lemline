# Feature Specification: Workflow Lifecycle Events

**Feature Branch**: `002-lifecycle-events`
**Created**: 2025-12-08
**Status**: Draft
**Input**: User description: "Implement the Lifecycle Events features of the Serverless Workflow specs to publish CloudEvents to a dedicated channel in real-time"

## Clarifications

### Session 2025-12-08

- Q: Where should lifecycle event hooks be captured (runner only vs core+runner)? → A: Capture lifecycle hooks in `StepByStepOrchestrator` (lemline-core), emit from runner. This provides accurate semantics where `task.started` fires when execution actually begins (via `ResumeFromTask`), not when scheduled.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Monitor Workflow Execution Progress (Priority: P1)

As an operations team member, I want to receive real-time notifications when workflows start and complete so that I can monitor system health and track execution metrics.

**Why this priority**: This is the foundational use case - knowing when workflows begin and end is essential for any observability system. Without this, users cannot build dashboards, alerting, or audit trails.

**Independent Test**: Can be fully tested by starting a workflow and verifying CloudEvents are published to the lifecycle channel at workflow start and completion. Delivers immediate value for monitoring and metrics collection.

**Acceptance Scenarios**:

1. **Given** a workflow is triggered, **When** execution begins, **Then** a `workflow.started` CloudEvent is published containing the workflow name, namespace, version, and instance ID
2. **Given** a workflow is executing, **When** it completes successfully, **Then** a `workflow.completed` CloudEvent is published containing the workflow identifier and completion timestamp
3. **Given** a workflow is executing, **When** it fails with an unhandled error, **Then** a `workflow.faulted` CloudEvent is published containing error details (type, title, status)

---

### User Story 2 - Track Task-Level Execution (Priority: P2)

As a developer debugging a workflow, I want to receive notifications when individual tasks start, complete, fail, or are retried so that I can identify bottlenecks and troubleshoot issues at a granular level.

**Why this priority**: Task-level events provide granular observability needed for debugging and performance optimization. This builds on P1 by adding depth to the monitoring capability.

**Independent Test**: Can be tested by running a workflow with multiple tasks and verifying each task emits appropriate lifecycle events. Delivers value for debugging and performance analysis.

**Acceptance Scenarios**:

1. **Given** a workflow task begins execution, **When** processing starts (ResumeFromTask command executes), **Then** a `task.started` CloudEvent is published containing the task reference (JSON Pointer location)
2. **Given** a task completes successfully, **When** it finishes, **Then** a `task.completed` CloudEvent is published with completion timestamp
3. **Given** a task fails, **When** an error occurs, **Then** a `task.faulted` CloudEvent is published with error details
4. **Given** a task is configured with retry policy, **When** a retry attempt begins, **Then** a `task.retried` CloudEvent is published

---

### User Story 3 - Build External Integrations (Priority: P3)

As a system integrator, I want to consume workflow lifecycle events from a dedicated message channel so that I can build custom integrations (alerting, analytics, audit logging) without modifying the workflow engine.

**Why this priority**: Enables ecosystem integrations and custom tooling. This story validates that the architecture supports external consumption of lifecycle events.

**Independent Test**: Can be tested by configuring an external consumer on the lifecycle channel and verifying it receives events in CloudEvents format. Delivers value for building third-party integrations.

**Acceptance Scenarios**:

1. **Given** the lifecycle events channel is configured, **When** any lifecycle event occurs, **Then** the event is available for consumption by external systems
2. **Given** an external consumer subscribes to lifecycle events, **When** workflows execute, **Then** events are delivered in standard CloudEvents format (JSON structured content mode)
3. **Given** high workflow throughput, **When** many events are generated, **Then** lifecycle event publication does not significantly impact workflow execution performance

---

### Edge Cases

- What happens when the lifecycle events channel is unavailable? Events should be published on a best-effort basis without blocking workflow execution
- How does the system handle rapid task execution where multiple tasks complete within milliseconds? Each task state change generates its own event with accurate timestamps
- What happens when a workflow has deeply nested tasks (try/catch, foreach, fork branches)? Task events include the full JSON Pointer reference to uniquely identify the task location

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST publish workflow lifecycle events as CloudEvents to a dedicated messaging channel separate from the commands/events channels
- **FR-002**: System MUST emit `workflow.started` event when a new workflow instance begins execution, including workflow namespace, name, version, and instance ID
- **FR-003**: System MUST emit `workflow.completed` event when a workflow instance completes successfully, including completion timestamp
- **FR-004**: System MUST emit `workflow.faulted` event when a workflow instance fails with an unhandled error, including error type, status, and title
- **FR-005**: System MUST emit `task.started` event when a task actually begins execution (when `ResumeFromTask` command is processed), including the task's JSON Pointer reference within the workflow
- **FR-006**: System MUST emit `task.completed` event when a task completes successfully
- **FR-007**: System MUST emit `task.faulted` event when a task fails
- **FR-008**: System MUST emit `task.retried` event when a task retry attempt begins
- **FR-009**: All lifecycle CloudEvents MUST include standard CloudEvents attributes: `specversion`, `id`, `source`, `type`, `time`
- **FR-010**: All lifecycle CloudEvents MUST include the workflow instance ID in a way that enables correlation of events for the same workflow execution
- **FR-011**: Lifecycle event publication MUST NOT block or significantly delay workflow execution (fire-and-forget pattern)
- **FR-012**: Lifecycle events MUST be published in real-time as state transitions occur
- **FR-013**: Lifecycle event data MUST be captured via hooks in `StepByStepOrchestrator` (lemline-core), with actual emission handled by lemline-runner

### Out of Scope (Not Implemented)

The following Serverless Workflow lifecycle events are out of scope for this initial implementation as Lemline does not yet support the underlying features:

- `workflow.suspended` / `workflow.resumed` - Manual workflow suspension not implemented
- `workflow.cancelled` - Workflow cancellation not implemented
- `workflow.correlation-started` / `workflow.correlation-completed` - Event correlation tracking not implemented
- `task.created` - Tasks are created and started atomically in Lemline (TaskScheduled internal event corresponds to scheduling, not the Serverless Workflow `task.created` semantic)
- `task.suspended` / `task.resumed` - Task-level suspension not implemented
- `task.cancelled` - Task cancellation not implemented
- `workflow.status-changed` / `task.status-changed` - Optional convenience events not required

### Key Entities

- **Lifecycle CloudEvent**: A CloudEvent notification representing a workflow or task state change, conforming to CloudEvents v1.0 specification
- **Lifecycle Channel**: A dedicated messaging channel for lifecycle events, separate from workflow commands and internal events
- **Workflow Reference**: Combination of namespace, name, and version that identifies a workflow definition
- **Task Reference**: JSON Pointer path that uniquely identifies a task's location within a workflow definition
- **Lifecycle Hook**: A callback interface in `StepByStepOrchestrator` that captures lifecycle event data at the point of state transition

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of workflow executions emit both `started` and either `completed` or `faulted` events
- **SC-002**: 100% of task executions emit `started` and either `completed`, `faulted`, or `retried` events as appropriate
- **SC-003**: All emitted events conform to CloudEvents v1.0 specification and can be parsed by standard CloudEvents libraries
- **SC-004**: Lifecycle event publication adds less than 5% overhead to workflow execution time under normal conditions
- **SC-005**: Events are published within 100ms of the corresponding state transition occurring
- **SC-006**: External consumers can successfully subscribe to and receive lifecycle events from the dedicated channel

## Assumptions

- The existing messaging infrastructure (Kafka/RabbitMQ) can support an additional topic/channel for lifecycle events
- CloudEvents JSON structured content mode is the appropriate format (vs. binary content mode)
- Best-effort delivery is acceptable for lifecycle events (they are observability data, not critical workflow state)
- The `source` attribute for CloudEvents will use a consistent identifier for the Lemline runtime
- Task lifecycle events cover the task types currently supported: HTTP calls, scripts, shell commands, wait, emit, listen, run workflow, and fork branches
- `StepByStepOrchestrator` can be extended with lifecycle hooks without breaking existing functionality
