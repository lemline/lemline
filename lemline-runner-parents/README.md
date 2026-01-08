# lemline-runner-parents

> Parent-child workflow relationships for `run` task

## Purpose

This module implements parent-child workflow relationships from the `run` task:
- **Child workflow spawning** when a workflow calls another workflow
- **Parent state preservation** while child executes
- **Result propagation** from child to parent on completion/failure

## Serverless Workflow DSL Reference

See [Run Task](https://serverlessworkflow.io/spec/latest/dsl-reference/#run) in the Serverless Workflow specification:

```yaml
do:
  - callChildWorkflow:
      run:
        workflow:
          namespace: myNamespace
          name: childWorkflow
          version: "1.0.0"
        input:
          orderId: ${ .orderId }
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    lemline-runner-parents                       │
├─────────────────────────────────────────────────────────────────┤
│  ParentService              ← Business logic for parent-child  │
│  ├── handleRunWorkflowStarted() ← Create parent, start child   │
│  ├── resumeParentOnChildCompletion() ← Propagate success       │
│  └── resumeParentOnChildFailure()    ← Propagate error         │
│                                                                 │
│  ParentModel                ← Parent-child relationship        │
│  ├── id                     ← Derived from position + step     │
│  ├── instanceMessage        ← Parent state for resumption      │
│  ├── childId                ← WorkflowId of child              │
│  ├── completedAt            ← When child finished              │
│  └── cleanupAfter           ← Eligible for deletion            │
│                                                                 │
│  ParentRepository           ← Database operations              │
│  ParentCleaner              ← Cleanup completed relationships  │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Parent State** | Preserved in database while child executes |
| **Child ID** | Deterministic ID derived from parent ID |
| **Sync vs Async** | Sync: parent waits; Async: parent continues (fire-and-forget) |
| **Idempotent Resume** | Parent only resumed once per child completion |

## File Reference

| File | Responsibility |
|------|----------------|
| `ParentService.kt` | Handle run started, child completion, child failure events |
| `ParentModel.kt` | Parent-child relationship entity |
| `ParentRepository.kt` | Database operations for parent records |
| `ParentCleaner.kt` | Scheduled cleanup of completed relationships |
| `ParentFeatureConfig.kt` | Configuration for parent feature |

## How It Works

### Child Workflow Spawning Flow

```
┌─────────────┐   RunWorkflowStarted   ┌─────────────────┐
│   Parent    │ ─────────────────────▶ │  ParentService  │
│  Workflow   │                        │                 │
└─────────────┘                        └────────┬────────┘
                                                │
                                 ┌──────────────┴──────────────┐
                                 │                             │
                                 ▼                             ▼
                        ┌─────────────────┐           ┌─────────────────┐
                        │ Insert parent   │           │ Emit child      │
                        │ record with     │           │ workflow        │
                        │ parent state    │           │ message         │
                        └─────────────────┘           └────────┬────────┘
                                                               │
                                                               ▼
                                                      ┌─────────────────┐
                                                      │     Child       │
                                                      │   Executes...   │
                                                      └────────┬────────┘
                                                               │
                               ┌───────────────────────────────┤
                               │                               │
                               ▼                               ▼
                      WorkflowCompleted              WorkflowFaulted
                               │                               │
                               ▼                               ▼
                      ┌─────────────────┐           ┌─────────────────┐
                      │ Resume parent   │           │ Resume parent   │
                      │ with child      │           │ with child      │
                      │ output          │           │ error           │
                      └─────────────────┘           └─────────────────┘
```

### Parent Resume Logic

1. **Find parent** by child workflow ID
2. **Check idempotency** - skip if already completed
3. **Resume parent** with child output or error
4. **Mark completed** and schedule for cleanup

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (event handlers) |
| `lemline-runner-schedules` | - |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Custom child ID generation** | Modify ID derivation in `ParentService` |
| **Child cancellation** | Add cancellation logic in parent service |

## Database Table

### `lemline_parents`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Derived from position + step |
| `instance_message` | TEXT | Serialized parent workflow state |
| `child_id` | UUID | Workflow ID of the spawned child |
| `completed_at` | TIMESTAMP | When child finished |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |
| `created_at` | TIMESTAMP | Record creation time |

### Index

```sql
CREATE INDEX idx_lemline_parents_child_id ON lemline_parents(child_id);
```

Used for efficient lookup when child workflow completes.
