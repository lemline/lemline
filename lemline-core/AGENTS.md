# lemline-core

Stateless workflow execution engine implementing Serverless Workflow DSL v1.0.

## Before Working Here

**Invoke skill:** `core-dev`

The skill provides: processor patterns, state management, exception-driven control flow, testing patterns.

## Critical Rules

- Processors are **stateless** - receive state, return updated state
- Use `AsyncTaskException` for wait/fork/runWorkflow (not regular exceptions)
- All `TaskState` subclasses must be `@Serializable`
- Test with `FullOrchestrator` for unit tests
