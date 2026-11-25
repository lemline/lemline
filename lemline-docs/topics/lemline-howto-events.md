---
title: How to emit and listen for events
---

# How to emit and listen for events

An `emit` task publishes a CloudEvent. A `listen` task resumes an existing workflow when
the selected event filters match. To start independent instances on matching events,
configure `schedule.on` at the workflow level.

## Prepare the examples

Complete [the SQLite setup](lemline-tutorial-setup.md). Run the commands from the setup directory. With the JAR,
substitute `java -jar lemline.jar` for `bin/lemline`. The examples need no shop: events travel on the worker's
SQLite event channel. This publisher sends the event type and data given as its input. Save it as
`tutorial-publisher.yaml` (skip it if you registered it in the tutorial):

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

Save the four definitions of this page under the file names used below and register them all before you start the
worker, so that it loads the event-started workflow of the last section. If the setup worker is running, stop it with Ctrl+C first.

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f emit-order-created.yaml
bin/lemline definition post -f listen-for-payment.yaml
bin/lemline definition post -f listen-until-complete.yaml
bin/lemline definition post -f order-event-schedule.yaml
bin/lemline listen --debug
```

Run the commands of each section from a second terminal in the setup directory.

## Emit an order event

Set the CloudEvent type and source in `emit.event.with`. You can include business data
in `data` and use `subject` to identify the business entity.

<code-block lang="yaml" src="workflows/reference/emit-order-created.yaml"/>

Save it as `emit-order-created.yaml`. It emits `com.example.order.created`, the event that starts the workflow of
[the last section](#start-a-workflow-on-an-event):

```bash
bin/lemline instance start examples emit-order-created --input '{"orderId":"ORD-1001"}'
```

The emitting instance completes at once. About 10 seconds later, the worker logs a new `order-event-schedule`
instance that completes with `{"orderId":"ORD-1001","status":"received"}`: the worker picks up events for
event-started workflows every 10 seconds.

## Wait for a correlated event

Use `listen.to.one` to match a single event. Use `correlate` to compare a value taken
from the event with a value from the workflow, such as an order ID. Set `timeout.after`
to bound the wait.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

Save it as `listen-for-payment.yaml`. Start a listener for `ORD-123`, then publish a payment for another order and one for `ORD-123`:

```bash
bin/lemline instance start examples listen-for-payment --input '{"orderId":"ORD-123"}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"OTHER"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"ORD-123"}}'
```

The payment for `OTHER` leaves the listener waiting. The second payment completes it with `[{"orderId":"ORD-123"}]`.

## Collect events and stop on a signal

Use `any` to collect matching updates and `until` to stop listening on a separate
event. Use `all` when every configured filter must match.

<code-block lang="yaml" src="workflows/reference/listen-until-complete.yaml"/>

Save it as `listen-until-complete.yaml`. Start it, send two updates, then the completion signal:

```bash
bin/lemline instance start examples listen-until-complete
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.updated","data":{"step":"packed"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.updated","data":{"step":"labelled"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.completed","data":{"orderId":"ORD-1002"}}'
```

The instance completes after the completion event with the two collected updates, `[{"step":"packed"},{"step":"labelled"}]`.

## Start a workflow on an event

Use a workflow-level schedule for event-triggered instances.

<code-block lang="yaml" src="workflows/reference/order-event-schedule.yaml"/>

Save it as `order-event-schedule.yaml`. Register it before starting the worker, as in [the preparation](#prepare-the-examples):
a workflow with `schedule.on` cannot be started with `instance start`. Each matching event starts a new instance,
whose input is the list of events that started it; the workflow-level `input.from` takes the first one.
The [emit example](#emit-an-order-event) shows it in action.
See [Event Correlation](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#correlation) for the expression scopes involved
and the [Listen Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#listen) for matching strategies and read modes.
