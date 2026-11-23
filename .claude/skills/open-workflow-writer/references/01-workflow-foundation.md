# Workflow structure and triggers

Set `document.dsl` to `1.0.3`. Namespace and name are single DNS labels (up to 63 characters, internal hyphens allowed). Give the definition its own semantic version. Each entry of `do` has one task name as its key.

Use a five-field Unix cron expression for a nightly trigger. Fetch the current batch at execution time when its contents change each night. For `schedule.on`, the triggering elements arrive as an array; the first task receives the array. Read an identifier as `$workflow.input[0].incidentId` (default data read mode) or transform the array in the first task. A workflow-level `input.from` alone does not reshape the first task’s dataset in Lemline.

[Lemline documentation](../../../../lemline-docs/topics/dsl-workflow-definition.md)
