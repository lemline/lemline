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

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

The event examples need the tutorial publisher. Save it as `tutorial-publisher.yaml`:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f interpret-deadline.yaml
bin/lemline instance start examples interpret-deadline --input '{"deadline":"2020-01-01T00:00:00Z"}'
bin/lemline instance start examples interpret-deadline --input '{"deadline":"2999-01-01T00:00:00Z"}'
bin/lemline definition post -f delayed-processing.yaml
bin/lemline instance start examples delayed-processing
bin/lemline definition post -f timeout-listen-fallback.yaml
bin/lemline instance start examples timeout-listen-fallback
bin/lemline definition post -f scheduled-processing.yaml
bin/lemline instance start examples scheduled-processing
sqlite3 orchestration.db "SELECT COUNT(*) FROM lemline_schedules"
```

| Example | Expected result |
|---|---|
| `interpret-deadline`, past deadline | `{"overdue":true}` |
| `interpret-deadline`, future deadline | The input unchanged: the `if` skips the task |
| `delayed-processing` | `{"state":"resumed"}` 30 to 40 seconds later |
| `timeout-listen-fallback` | `{"status":"expired"}` 30 to 40 seconds later, with no approval event |
| `scheduled-processing` | `instance start` registers the schedule: the query counts one schedule. No instance runs before the next weekday at 9:00. |

