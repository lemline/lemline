# lemline-runner-event-schedules

Durable event-triggered workflow starts for `schedule.on`.

## Event-triggered workflows

Posting a definition with `schedule.on` activates its trigger locally. Other workers discover it
on the next definition-cache synchronization (default: 10 seconds). No manual start is required. Publish a CloudEvent to the definition namespace's CloudEvents destination:

```yaml
document:
  dsl: '1.0.0'
  namespace: orders
  name: order-created
  version: '1.0.0'
schedule:
  on:
    one:
      with:
        type: order.created
do:
  - process:
      set:
        order: ${ $workflow.input[0].data }
```

The DSL 1.0.0 input is an array of triggering CloudEvent envelopes, including `data`, even for
`one`. `one` and `any` start a new instance on each matching event; `any: []` matches every event.
`all` collects every filter before starting. Repeated events for an already-filled filter open
the next collection, so `A1, A2, B1, B2` yields `A1+B1` and `A2+B2`. Events matching multiple
filters appear once in the input. Input order follows arrival order.

Correlation uses the existing event filter expressions and keeps independent collections for
different correlation values. `any.until` supports expressions over the collected envelope array
and event strategies (`one`, `any`, `all`). Termination events are included only if they also
match the main strategy. Completed collections automatically rearm. Every posted version is
eligible, and only definitions in the event's arrival namespace can trigger.
The termination subscription is active before the first main event; if it completes first, the
workflow receives an empty array (subject to its input schema).

Manual starts (including workflow calls through `Starter`) return a validation error explaining
that the workflow starts automatically from matching CloudEvents.

### Durability and lifecycle

`EventScheduleService` selects candidates from the definition cache using the same parsed filters
and matcher as `listen`. It reads and locks only matching candidate definitions while updating their
collections, recording the CloudEvent `source + id`, and inserting a start into
`lemline_event_schedule_starts`. All three changes commit together. Multiple workers and broker
redelivery cannot create another start for the same event and definition. The outbox uses the
same persisted command and message ID on retries; delivery is at least once, as with other relays.
Collected input is checked against `workflow.input.schema` before a start is accepted. Invalid
input is logged and consumed without creating an instance; the collection rearms and its event
receipts remain deduplicated. Other failures roll back so broker retries can recover.

Forced definition changes discard incomplete collections on the next event; deletion cascades
to collection state and receipts. Already accepted starts remain durable. Each candidate is rechecked against its locked database definition, so stale positive matches
cannot start a workflow using an obsolete trigger.

The relay and start cleaner use the existing listener outbox/cleanup settings and namespace scope.
Migrations are provided for PostgreSQL, MySQL, and H2.

### Operational tradeoffs

The shared definition cache publishes workflow nodes, listen filters and schedule filters atomically.
The first CloudEvent waits for the initial catalog load; a failed load fails processing for retry.
Periodic reconciliation adds, updates and removes definitions, reusing unchanged parsed entries.
If a local cache write overlaps a catalog query, synchronization discards that snapshot and retries.
Local definition deletion invalidates after commit; caller-owned transactions rely on periodic
reconciliation after the caller commits. Programmatically-built workflows remain outside catalog ownership. Function node trees check the
supplied task identity, so updated definitions rebuild their function bodies and listen filters.

Processing serializes per candidate definition. Nonmatching events perform no schedule database reads.
Catalog scans occur only at initialization and during periodic synchronization, not on each event.
A new or changed trigger published by another process can miss events until the next successful sync;
those events are not replayed automatically. The locked candidate recheck prevents stale positive
matches, but cannot discover a newly matching filter absent from the cache.
Incomplete collections have no automatic expiry. Event receipts remain until the definition is
deleted, independently of completed-start cleanup, so delayed redelivery remains deduplicated.
Plan storage for long-lived, high-volume definitions and collections whose conditions never complete.
