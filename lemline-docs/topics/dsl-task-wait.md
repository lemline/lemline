# Wait Task

A `wait` task pauses an existing workflow instance for a duration.

## Wait for a duration

Set `wait` to an ISO 8601 duration such as `PT30S` or an object such as `{ seconds: 30 }`.
The next task receives the input carried through the wait.

<code-block lang="yaml" src="workflows/reference/delayed-processing.yaml"/>

## Run at a scheduled time

Use `schedule.cron` to start a new instance at each scheduled time. Here, the expression matches the
start of every minute.

<code-block lang="yaml" src="workflows/dsl-task-wait/cron-stock-report.yaml"/>

## Wait for an external event

Use `listen` to pause an existing instance until an event arrives. This workflow waits for a payment
event whose order ID matches the input. A different order ID leaves it waiting.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

The publisher sends a payment event with the order ID supplied on the command line.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

See [Listen](dsl-task-listen.md) and [Event Correlation](dsl-event-correlation.md) for event filters.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) in a fresh project directory and leave the worker
running. Save each workflow under the file name used below. Use a separate project for this exercise
because the cron schedule persists in SQLite.

```bash
bin/lemline definition post -f delayed-processing.yaml
bin/lemline instance start examples delayed-processing
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f listen-for-payment.yaml
bin/lemline instance start examples listen-for-payment --input '{"orderId":"O-130"}'
```

Start the listener and wait for the worker to register it before sending the matching event.

```bash
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"O-130"}}'
```

> **Lemline**
>
> Starting the cron workflow registers the schedule. Look in the worker terminal for the
> first scheduled occurrence, which has its own instance ID. The worker can take a few seconds after
> the minute boundary to pick it up.

```bash
bin/lemline definition post -f cron-stock-report.yaml
bin/lemline instance start examples cron-stock-report --zone UTC
sleep 75
```

| Example | Expected result |
|---|---|
| `delayed-processing` | Returns `{"state":"resumed"}` after waiting at least 30 seconds. |
| `listen-for-payment` | Waits for the matching event, then returns `[{"orderId":"O-130"}]`. |
| `tutorial-publisher` | Completes after emitting the matching payment event. |
| `cron-stock-report` | The first scheduled occurrence returns `{"report":"stock"}`. |

After checking the results, stop the worker in the separate project with Ctrl+C.
