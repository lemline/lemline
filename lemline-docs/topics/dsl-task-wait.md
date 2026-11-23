# Wait Task

A `wait` task pauses a workflow for a duration. Lemline persists the wait so the runner
can resume the workflow when the duration elapses.

## Wait for a duration

Set `wait` to an ISO 8601 duration such as `PT30S` or a duration object such as
`{ seconds: 30 }`. The next task receives the input carried through the wait.

<code-block lang="yaml" src="workflows/reference/delayed-processing.yaml"/>

## Run at a scheduled time

Use `schedule.cron` on the workflow definition when the schedule should start independent
workflow instances. The runner evaluates the schedule separately from workflow input.

<code-block lang="yaml" src="workflows/reference/scheduled-processing.yaml"/>

## Wait for an external event

Use `listen` when an external event should resume an existing instance. A listen timeout
can bound the wait.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

See the [Listen Task](dsl-task-listen.md) and [Event Correlation](dsl-event-correlation.md)
for event filters and correlation.
