# Choosing tasks from intent

| Requirement | DSL choice |
|---|---|
| Fetch or update a resource | `call: http` with the specified verb |
| Route one application by score | `switch` and a shared join |
| Process each member of a batch | `for` with a named element |
| Wait for approval | Correlated `listen` |
| Wait five minutes | `wait` |
| Notify several services, first success wins | `fork` with `compete: true` |
| Confirm a completed business action | `emit` after the successful path |
| Recover from a transient failure | `try`, filtered retry and a bounded limit |

[Lemline documentation](../../../../lemline-docs/topics/dsl-tasks-overview.md)
