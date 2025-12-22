# lemline-runner-forks

> Parallel branch execution for fork/join patterns

## Purpose

This module implements the `fork` task from Serverless Workflow DSL:
- **Parallel execution** of multiple branches within a workflow
- **Compete mode** - first branch to complete wins
- **Cooperative mode** - wait for all branches to complete
- **Output assembly** - combine branch outputs into final result

## Serverless Workflow DSL Reference

See [Fork Task](https://serverlessworkflow.io/spec/latest/dsl-reference/#fork) in the Serverless Workflow specification:

```yaml
do:
  - processInParallel:
      fork:
        compete: false  # true = first wins, false = wait for all
        branches:
          - branch1:
              do: [...]
          - branch2:
              do: [...]
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     lemline-runner-forks                        │
├─────────────────────────────────────────────────────────────────┤
│  ForkService                ← Business logic for fork events   │
│  ├── handleForkStarted()    ← Create fork + emit branches      │
│  ├── handleBranchCompleted()← Check completion, assemble output│
│  └── handleBranchFailed()   ← Apply compete/coop error logic   │
│                                                                 │
│  ForkModel                  ← Fork metadata entity              │
│  ├── id                     ← Derived from position + step     │
│  ├── instanceMessage        ← Parent state for resumption      │
│  ├── position               ← Fork node position               │
│  ├── compete                ← true = first wins                 │
│  ├── output                 ← Assembled branch outputs         │
│  ├── completedAt            ← Fork completion timestamp        │
│  └── failedAt               ← Fork failure timestamp           │
│                                                                 │
│  ForkBranchModel            ← Individual branch tracking       │
│  ├── forkId                 ← Reference to parent fork         │
│  ├── name                   ← Branch name                      │
│  ├── output                 ← Branch output (JSON)             │
│  ├── completedAt            ← Branch completion                │
│  └── failedAt               ← Branch failure                   │
│                                                                 │
│  ForkRepository             ← Fork + branch DB operations      │
│  ForkBranchRepository       ← Branch-specific operations       │
│  ForkCleaner                ← Cleanup completed forks          │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Compete Mode** | First branch to complete wins; other branches are ignored |
| **Cooperative Mode** | All branches must complete; outputs assembled into array |
| **Idempotent IDs** | Fork/branch IDs derived from position + step for replay safety |
| **Pessimistic Locking** | Branch updates use transactions to prevent race conditions |

## File Reference

| File | Responsibility |
|------|----------------|
| `ForkService.kt` | Business logic for fork started/branch completed/failed events |
| `ForkModel.kt` | Fork metadata with parent state and completion tracking |
| `ForkBranchModel.kt` | Individual branch state and output |
| `ForkRepository.kt` | Fork and branch database operations |
| `ForkBranchRepository.kt` | Branch-specific queries |
| `ForkCleaner.kt` | Scheduled cleanup of completed forks |
| `ForkFeatureConfig.kt` | Configuration for fork feature |

## How It Works

### Fork Execution Flow

```
┌─────────────┐     ForkStarted      ┌─────────────┐
│   Workflow  │ ──────────────────▶  │ ForkService │
│  Processor  │                      │             │
└─────────────┘                      └──────┬──────┘
                                            │
                 ┌──────────────────────────┼──────────────────────────┐
                 │                          │                          │
                 ▼                          ▼                          ▼
          ┌──────────┐              ┌──────────┐              ┌──────────┐
          │ Branch 1 │              │ Branch 2 │              │ Branch N │
          └────┬─────┘              └────┬─────┘              └────┬─────┘
               │                         │                         │
               ▼                         ▼                         ▼
        BranchCompleted           BranchCompleted           BranchCompleted
               │                         │                         │
               └─────────────────────────┼─────────────────────────┘
                                         │
                                         ▼
                                ┌─────────────────┐
                                │  Check if fork  │
                                │  is complete    │
                                └────────┬────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │ compete=true      │ compete=false       │
                    │ First completion  │ All completions     │
                    ▼                   ▼                     │
              Resume parent       Assemble outputs            │
              with branch         Resume parent               │
              output              with array                  │
```

### Output Assembly

| Mode | Completion Trigger | Output Format |
|------|-------------------|---------------|
| **Compete** | First branch completes | Single branch output |
| **Cooperative** | All branches complete | `[branch1Output, branch2Output, ...]` |

### Error Handling

| Mode | Error Behavior |
|------|----------------|
| **Compete** | Wait for all branches; fail only if ALL fail |
| **Cooperative** | Fail immediately on first branch failure |

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (event handlers) |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Custom completion logic** | Modify `ForkService.handleBranchCompleted()` |
| **Branch cancellation** | Add cancellation in `handleBranchCompleted()` for compete mode |

## Database Tables

### `lemline_forks`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Derived from position + step |
| `instance_message` | TEXT | Serialized parent state |
| `position` | VARCHAR(255) | Fork node position |
| `compete` | BOOLEAN | true = first wins |
| `output` | TEXT | Assembled branch outputs (JSON) |
| `completed_at` | TIMESTAMP | Fork completion time |
| `failed_at` | TIMESTAMP | Fork failure time |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |
| `error_*` | Various | Error tracking fields |

### `lemline_fork_branches`

| Column | Type | Description |
|--------|------|-------------|
| `fork_id` | UUID | Reference to parent fork |
| `name` | VARCHAR(255) | Branch name |
| `output` | TEXT | Branch output (JSON) |
| `completed_at` | TIMESTAMP | Branch completion time |
| `failed_at` | TIMESTAMP | Branch failure time |
| `error_*` | Various | Error tracking fields |
