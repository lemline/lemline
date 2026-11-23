# Time in workflows

Workflows can compare timestamps in expressions, resume after a duration or start on
a schedule. Lemline persists pending work so it can resume after a runner restart.

## Compare timestamps in jq

Use `now` for Unix time in seconds and `fromdateiso8601` to convert an ISO 8601 UTC
timestamp before comparing it.

<code-block lang="yaml" src="workflows/reference/interpret-deadline.yaml"/>

## Delay an existing workflow instance

Use an ISO 8601 duration in a `wait` task to pause before continuing. The runner resumes
the same instance.

<code-block lang="yaml" src="workflows/reference/delayed-processing.yaml"/>

## Schedule new instances

Use `schedule.cron` when each matching time should start a new instance.

<code-block lang="yaml" src="workflows/reference/scheduled-processing.yaml"/>

## Bound a wait for an event

Use `timeout.after` on a listen task to bound how long it waits. If the task times out,
an enclosing try task can read the error and return a fallback.

<code-block lang="yaml" src="workflows/reference/timeout-listen-fallback.yaml"/>

See [Supported Features](lemline-ref-supported-features.md) for timeout behavior on
other task types.
