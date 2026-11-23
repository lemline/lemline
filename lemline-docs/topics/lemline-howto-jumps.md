---
title: How to jump between tasks (then, exit, end)
---

# How to jump between tasks (then, exit, end)

Tasks run in the order listed in `do`. Use a flow directive to override that order.

| Directive | Effect |
| --- | --- |
| `continue` | Advance to the next task in the current list |
| Task name | Move to the named task in the current list |
| `exit` | Leave the current task list and return to its parent |
| `end` | End the workflow |

## Select a task with switch

Each named case has a `then` directive. The first case whose `when` condition matches
wins. A final case without a condition provides a fallback.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

After setting its status, the manual-review path uses `then: end` to avoid falling
through to the automatic path.

## Exit a nested task list

Here a missing order ID exits the inner `do` list. The outer list resumes at `finalize`.

<code-block lang="yaml" src="workflows/reference/exit-nested-scope.yaml"/>

See [Flow Control](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl.md#task-flow) for task and workflow completion behavior.
