---
title: Workflow Definition Structure
---

# Workflow Definition Structure

A workflow is a YAML or JSON object. `document` identifies it and `do` lists its
named tasks.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Top-Level Properties

| Property | Purpose |
| --- | --- |
| `document` | Required `dsl`, `namespace`, `name`, and `version`. Optional titles, summaries and metadata also belong here. |
| `do` | Required list of named tasks. |
| `input` | Validate the supplied data or transform it before the first task. |
| `output` | Transform or validate the result. |
| `use` | Declare shared resources, including retry policies, authentication and extensions. |
| `schedule` | Start instances on a timer or when an event matches. |
| `evaluate` | Set expression evaluation options. |
| `timeout` | Describe a workflow timeout in the DSL. See [Timeouts](dsl-timeouts.md) for runtime enforcement. |

## Schedule workflows

Use `schedule.cron` for time-driven instances or `schedule.on` for event-driven ones. The cron example starts a stock report at the beginning of each minute.

<code-block lang="yaml" src="workflows/dsl-task-wait/cron-stock-report.yaml"/>

A matching order event starts the second workflow and supplies its input.

<code-block lang="yaml" src="workflows/reference/order-event-schedule.yaml"/>

Run the publisher as a separate instance to emit the order event.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

## Share resources

Use named resources when multiple tasks need the same policy or function. See
[Extension in the Open Workflow reference](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#extension) for the `use.extensions` section.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run the registration commands in a fresh setup directory for the manual examples, the event schedule, and the publisher.

```bash
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline definition post -f order-event-schedule.yaml
bin/lemline definition post -f tutorial-publisher.yaml
```

> **Lemline**
>
> Start or restart the worker after registering a `schedule.on` definition. Stop the setup worker with Ctrl+C and run the restart command in its terminal.

```bash
bin/lemline listen --debug
```

Use the second terminal to start the two workflows manually and publish the event that starts the third.

```bash
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"O-180","total":1250}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":2}'
sleep 5
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.created","data":{"orderId":"O-181"}}'
```

> **Lemline**
>
> Starting the cron workflow registers the schedule in SQLite. Wait for a scheduled occurrence with its own instance ID.

```bash
bin/lemline definition post -f cron-stock-report.yaml
bin/lemline instance start examples cron-stock-report --zone UTC
sleep 75
```

| Example | Expected result |
|---|---|
| `validate-and-route-order` | Returns `{"orderId":"O-180","status":"needs-review"}`. |
| `retry-and-fallback` | Returns the stock for `SKU-1` after two HTTP 503 responses. |
| `order-event-schedule` | The order event starts it and it returns `{"orderId":"O-181","status":"received"}`. |
| `tutorial-publisher` | Completes after emitting the order event. |
| `cron-stock-report` | The first scheduled occurrence returns `{"report":"stock"}`. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

Stop the worker with Ctrl+C when done. The cron schedule remains in this example’s SQLite project.
