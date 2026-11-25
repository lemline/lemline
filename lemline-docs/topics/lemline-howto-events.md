---
title: How to emit and listen for events
---

# How to emit and listen for events

An `emit` task publishes a CloudEvent. A `listen` task resumes an existing workflow when
the selected event filters match. To start independent instances on matching events,
configure `schedule.on` at the workflow level.

## Emit an order event

Set the CloudEvent type and source in `emit.event.with`. You can include business data
in `data` and use `subject` to identify the business entity.

<code-block lang="yaml" src="workflows/reference/emit-order-created.yaml"/>

## Wait for a correlated event

Use `listen.to.one` to match a single event. Use `correlate` to compare a value taken
from the event with a value from the workflow, such as an order ID. Set `timeout.after`
to bound the wait.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

## Collect events and stop on a signal

Use `any` to collect matching updates and `until` to stop listening on a separate
event. Use `all` when every configured filter must match.

<code-block lang="yaml" src="workflows/reference/listen-until-complete.yaml"/>

## Start a workflow on an event

Use a workflow-level schedule for event-triggered instances.

<code-block lang="yaml" src="workflows/reference/order-event-schedule.yaml"/>

An event-started instance receives the matched events as an array. The workflow-level
`input.from` unwraps the first one, so the tasks read the order directly.

See [Event Correlation](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#correlation) for the expression scopes involved
and the [Listen Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#listen) for matching strategies and read modes.
