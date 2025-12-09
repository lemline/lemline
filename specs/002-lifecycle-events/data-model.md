# Data Model: Workflow Lifecycle Events

**Feature**: 002-lifecycle-events
**Date**: 2025-12-08

## Overview

Lifecycle events are CloudEvents published to a messaging channel. There is **no database persistence** - events are fire-and-forget to the `lemline-lifecycle-events` topic.

## CloudEvents Data Structures

### Common CloudEvent Envelope

All lifecycle events share these CloudEvents context attributes:

| Attribute | Type | Description | Example |
|-----------|------|-------------|---------|
| `specversion` | String | CloudEvents spec version | `"1.0"` |
| `id` | String (UUID v7) | Unique event identifier | `"0192d4e5-..."` |
| `source` | URI | Event source identifier | `"urn:lemline:workflow:default:my-workflow:1.0.0"` |
| `type` | String | Event type (see below) | `"io.serverlessworkflow.workflow.started.v1"` |
| `time` | ISO 8601 | Event timestamp | `"2025-12-08T10:30:00.000Z"` |
| `datacontenttype` | String | Data content type | `"application/json"` |

### Lemline Extension Attributes

| Extension | Type | Description |
|-----------|------|-------------|
| `lemlineworkflowid` | String (UUID) | Workflow instance ID |
| `lemlineworkflownamespace` | String | Workflow namespace |
| `lemlineworkflowname` | String | Workflow name |
| `lemlineworkflowversion` | String | Workflow version |

---

## Workflow Lifecycle Events

### WorkflowCreatedEvent

**Type**: `io.serverlessworkflow.workflow.created.v1`

**Emitted**: When a workflow instance is created (before execution starts). This represents the intent to start a workflow.

**Data Payload**:
```json
{
  "name": "default/my-workflow:1.0.0",
  "input": { "orderId": "12345" },
  "createdAt": "2025-12-08T10:30:00.000Z",
  "definition": {
    "namespace": "default",
    "name": "my-workflow",
    "version": "1.0.0"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Qualified workflow name (namespace/name:version) |
| `input` | Object | Yes | Workflow input data |
| `createdAt` | ISO 8601 | Yes | Workflow creation timestamp |
| `definition.namespace` | String | Yes | Workflow namespace |
| `definition.name` | String | Yes | Workflow name |
| `definition.version` | String | Yes | Workflow version |

**Note**: This event is a Lemline extension (not part of the Serverless Workflow specification). It is emitted by the CLI and RunWorkflow task handlers after successfully sending the workflow command.

---

### WorkflowStartedEvent

**Type**: `io.serverlessworkflow.workflow.started.v1`

**Emitted**: When a workflow instance begins execution (first task starts).

**Data Payload**:
```json
{
  "name": "default/my-workflow:1.0.0",
  "startedAt": "2025-12-08T10:30:00.000Z",
  "definition": {
    "namespace": "default",
    "name": "my-workflow",
    "version": "1.0.0"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Qualified workflow name (namespace/name:version) |
| `startedAt` | ISO 8601 | Yes | Workflow start timestamp |
| `definition.namespace` | String | Yes | Workflow namespace |
| `definition.name` | String | Yes | Workflow name |
| `definition.version` | String | Yes | Workflow version |

---

### WorkflowCompletedEvent

**Type**: `io.serverlessworkflow.workflow.completed.v1`

**Emitted**: When a workflow instance completes successfully.

**Data Payload**:
```json
{
  "name": "default/my-workflow:1.0.0",
  "output": { "result": "success", "data": {} },
  "completedAt": "2025-12-08T10:31:00.000Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Qualified workflow name |
| `output` | Object | Yes | Workflow output data |
| `completedAt` | ISO 8601 | Yes | Workflow completion timestamp |

---

### WorkflowFaultedEvent

**Type**: `io.serverlessworkflow.workflow.faulted.v1`

**Emitted**: When a workflow instance fails with an unhandled error.

**Data Payload**:
```json
{
  "name": "default/my-workflow:1.0.0",
  "faultedAt": "2025-12-08T10:31:00.000Z",
  "error": {
    "type": "https://serverlessworkflow.io/errors/runtime",
    "status": 500,
    "title": "Task execution failed"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | String | Yes | Qualified workflow name |
| `faultedAt` | ISO 8601 | Yes | Fault timestamp |
| `error.type` | URI | Yes | Error type URI |
| `error.status` | Integer | Yes | HTTP-like status code |
| `error.title` | String | Yes | Human-readable error title |

---

## Task Lifecycle Events

### TaskCreatedEvent

**Type**: `io.serverlessworkflow.task.created.v1`

**Emitted**: When a task is scheduled/queued for execution (before it actually starts running).

**Data Payload**:
```json
{
  "workflow": "default/my-workflow:1.0.0",
  "task": "/do/0/callApi",
  "input": { "orderId": "12345" },
  "createdAt": "2025-12-08T10:30:04.000Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `workflow` | String | Yes | Qualified workflow name |
| `task` | String | Yes | JSON Pointer to task in workflow definition |
| `input` | Object | Yes | Task input data |
| `createdAt` | ISO 8601 | Yes | Task creation/scheduling timestamp |

---

### TaskStartedEvent

**Type**: `io.serverlessworkflow.task.started.v1`

**Emitted**: When a task begins execution.

**Data Payload**:
```json
{
  "workflow": "default/my-workflow:1.0.0",
  "task": "/do/0/callApi",
  "startedAt": "2025-12-08T10:30:05.000Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `workflow` | String | Yes | Qualified workflow name |
| `task` | String | Yes | JSON Pointer to task in workflow definition |
| `startedAt` | ISO 8601 | Yes | Task start timestamp |

---

### TaskCompletedEvent

**Type**: `io.serverlessworkflow.task.completed.v1`

**Emitted**: When a task completes successfully.

**Data Payload**:
```json
{
  "workflow": "default/my-workflow:1.0.0",
  "task": "/do/0/callApi",
  "output": { "status": "success", "data": {} },
  "completedAt": "2025-12-08T10:30:06.000Z"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `workflow` | String | Yes | Qualified workflow name |
| `task` | String | Yes | JSON Pointer to task |
| `output` | Object | Yes | Task output data |
| `completedAt` | ISO 8601 | Yes | Task completion timestamp |

---

### TaskFaultedEvent

**Type**: `io.serverlessworkflow.task.faulted.v1`

**Emitted**: When a task fails with an error.

**Data Payload**:
```json
{
  "workflow": "default/my-workflow:1.0.0",
  "task": "/do/0/callApi",
  "faultedAt": "2025-12-08T10:30:06.000Z",
  "error": {
    "type": "https://serverlessworkflow.io/errors/communication",
    "status": 503,
    "title": "HTTP call failed"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `workflow` | String | Yes | Qualified workflow name |
| `task` | String | Yes | JSON Pointer to task |
| `faultedAt` | ISO 8601 | Yes | Fault timestamp |
| `error.type` | URI | Yes | Error type URI |
| `error.status` | Integer | Yes | HTTP-like status code |
| `error.title` | String | Yes | Human-readable error title |

---

### TaskRetriedEvent

**Type**: `io.serverlessworkflow.task.retried.v1`

**Emitted**: When a task retry attempt begins.

**Data Payload**:
```json
{
  "workflow": "default/my-workflow:1.0.0",
  "task": "/do/0/callApi",
  "retriedAt": "2025-12-08T10:30:07.000Z",
  "retryCount": 1
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `workflow` | String | Yes | Qualified workflow name |
| `task` | String | Yes | JSON Pointer to task |
| `retriedAt` | ISO 8601 | Yes | Retry timestamp |
| `retryCount` | Integer | Yes | Current retry attempt number (1-based) |

---

## Entity Relationships

```
┌─────────────────────────────────────────────────────────────────┐
│                    Workflow Instance                             │
│  (identified by lemlineworkflowid extension attribute)          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐                                            │
│  │ workflow.created │  (Lemline extension)                      │
│  └────────┬────────┘                                            │
│           │                                                      │
│           ▼                                                      │
│  ┌─────────────────┐                                            │
│  │ workflow.started │────────────────────────────────────────┐  │
│  └─────────────────┘                                         │  │
│           │                                                  │  │
│           ▼                                                  │  │
│  ┌─────────────────┐   ┌─────────────────┐                  │  │
│  │  task.started   │──▶│  task.completed │                  │  │
│  └─────────────────┘   └─────────────────┘                  │  │
│           │                    │                             │  │
│           │            ┌──────┴──────┐                       │  │
│           │            ▼             ▼                       │  │
│           │   ┌─────────────┐ ┌─────────────┐               │  │
│           │   │ task.faulted│ │ task.retried│───┐           │  │
│           │   └─────────────┘ └─────────────┘   │           │  │
│           │            │             ▲          │           │  │
│           │            │             └──────────┘           │  │
│           │            │                                    │  │
│           ▼            ▼                                    ▼  │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                workflow.completed                          │ │
│  │                      OR                                    │ │
│  │                workflow.faulted                            │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## State Transitions

### Workflow State Machine

```
                    ┌─────────┐
         create ───▶│ CREATED │  (Lemline extension)
                    └────┬────┘
                         │
                    ┌────▼────┐
         start ────▶│ RUNNING │
                    └────┬────┘
                         │
            ┌────────────┼────────────┐
            ▼            │            ▼
     ┌───────────┐       │     ┌───────────┐
     │ COMPLETED │       │     │  FAULTED  │
     └───────────┘       │     └───────────┘
                         │
                   (task events)
```

### Task State Machine

```
         start
           │
           ▼
     ┌───────────┐
     │  STARTED  │
     └─────┬─────┘
           │
     ┌─────┴─────┐
     ▼           ▼
┌─────────┐ ┌─────────┐
│COMPLETED│ │ FAULTED │◀───┐
└─────────┘ └────┬────┘    │
                 │         │
                 ▼         │
            ┌─────────┐    │
            │ RETRIED │────┘
            └─────────┘
```

## Validation Rules

### Event ID
- Must be valid UUID v7 format
- Must be deterministically derivable from workflow state for idempotency

### Timestamps
- All timestamps must be ISO 8601 format with millisecond precision
- `startedAt` must precede `completedAt` or `faultedAt`

### Task Reference (JSON Pointer)
- Must be valid JSON Pointer (RFC 6901)
- Must reference a valid task location in the workflow definition
- Examples: `/do/0/taskName`, `/do/0/try/do/1/nestedTask`

### Error Structure
- `type` must be a valid URI
- `status` must be a valid HTTP status code (4xx or 5xx for errors)
- `title` must be non-empty string
