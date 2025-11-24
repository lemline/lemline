# Lemline Core Documentation

The `lemline-core` module implements the Serverless Workflow DSL v1.0 specification, providing a pure, stateless execution engine.

## Documentation Index

| Document | Description | When to Read |
|----------|-------------|--------------|
| [core-overview.md](core-overview.md) | Module structure, DSL parsing, adding new task types | Starting point, adding features |
| [core-nodes.md](core-nodes.md) | Node tree architecture, NodePosition, navigation | Working with workflow structure |
| [core-orchestrators.md](core-orchestrators.md) | StepByStep and Full orchestrators | Execution control, testing |
| [core-processors.md](core-processors.md) | NodeProcessor pattern, control flows, activities | Implementing task logic |
| [core-fork.md](core-fork.md) | Parallel branches, error boundaries | Fork/join patterns |
| [core-errors.md](core-errors.md) | Exceptions, retry policies, error handling | Error handling, debugging |
| [core-states.md](core-states.md) | TaskState, WorkflowState, commands/events | State management |
| [core-expressions.md](core-expressions.md) | JQ expressions, scope, evaluation | Data transformation |

## Quick Reference

### Key Classes

| Class | Location | Purpose |
|-------|----------|---------|
| `DefinitionCache` | `definitions/` | Parse and cache workflows |
| `StepByStepOrchestrator` | `orchestrator/` | Production execution |
| `FullOrchestrator` | `orchestrator/` | Testing execution |
| `Node<T>` | `nodes/` | Immutable workflow node |
| `NodePosition` | `nodes/` | Node addressing |
| `NodeProcessor<T,S>` | `processors/` | Task execution interface |
| `TaskState` | `states/` | Execution state base |
| `JQExpression` | `expressions/` | Expression evaluator |

### Common Tasks

| Task | Documentation |
|------|---------------|
| Add new task type | [core-overview.md](core-overview.md#adding-a-new-task-type) |
| Create processor | [core-processors.md](core-processors.md#creating-a-new-processor) |
| Handle errors | [core-errors.md](core-errors.md#error-handling-flow) |
| Test workflows | [core-orchestrators.md](core-orchestrators.md#usage-examples) |
| Debug navigation | [core-nodes.md](core-nodes.md#debugging) |
| Write expressions | [core-expressions.md](core-expressions.md#common-jq-patterns) |
