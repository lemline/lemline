# lemline-runner-listeners

> CloudEvent listeners for the `listen` task

## Purpose

This module implements the `listen` task from Serverless Workflow DSL:
- **Wait for CloudEvents** matching specified filters
- **Multiple strategies**: ONE, ANY, ALL, with optional `until` conditions
- **Correlation** matching events to the correct workflow instance
- **Foreach processing** for iterative event handling

## Serverless Workflow DSL Reference

See [Listen Task](https://serverlessworkflow.io/spec/latest/dsl-reference/#listen) in the Serverless Workflow specification:

```yaml
do:
  - waitForEvent:
      listen:
        to:
          one:  # or any, all
            with:
              type: order.created
              source: /orders
        until:
          events:
            with:
              type: order.timeout
        foreach:
          item: event
          do:
            - processEvent: {...}
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                   lemline-runner-listeners                      │
├─────────────────────────────────────────────────────────────────┤
│  ListenerService            ← Listener lifecycle operations    │
│  └── handleListenStarted()  ← Create listener record           │
│                                                                 │
│  ListenerEventService       ← CloudEvent and event processing  │
│  ├── handleCloudEvent()     ← Process incoming CloudEvents     │
│  └── handleListenForEachCompleted() ← Mark foreach iteration done│
│                                                                 │
│  ListenerModel              ← Active listener entity            │
│  ├── id                     ← Derived from position + step     │
│  ├── instanceMessage        ← Workflow state for resumption    │
│  ├── strategy               ← ONE/ANY/ANY_UNTIL_*/ALL          │
│  ├── timeoutAt              ← Optional timeout timestamp       │
│  ├── correlationValues      ← Expected correlation values      │
│  ├── filtersCount           ← Number of filters (for ALL)      │
│  ├── hasForeach             ← Has foreach.do configured        │
│  ├── hasUntil               ← Has until condition              │
│  └── readyAt                ← Completion criteria met          │
│                                                                 │
│  ListenerEventModel         ← Matched CloudEvent               │
│  ├── listenerId             ← Reference to parent listener     │
│  ├── eventId                ← CloudEvent ID                    │
│  ├── filterName             ← Matched filter name              │
│  ├── cloudEvent             ← Full CloudEvent JSON             │
│  └── output                 ← Foreach iteration output         │
│                                                                 │
│  ListenerStrategy           ← Enum: ONE, ANY, ANY_UNTIL_*, ALL │
│                                                                 │
│  outbox/                                                        │
│  ├── ListenerCompletionOutbox ← Resume when closed_at is set│
│  ├── ListenerForeachOutbox    ← Process foreach iterations     │
│  └── ListenerScheduledTimeout ← Handle timeouts                │
│                                                                 │
│  cloudevents/                                                   │
│  └── CloudEventService      ← Match incoming events            │
│                                                                 │
│  cleaner/                                                       │
│  └── ListenerCleaner        ← Cleanup completed listeners      │
└─────────────────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Strategy** | How many events to wait for: ONE, ANY (first match), ALL (one per filter) |
| **Until Condition** | Stop accumulating when expression/event matches |
| **Correlation** | Match events to specific workflow instances via `expect` expressions |
| **Foreach** | Process each matched event through a sub-workflow |
| **ReadAs** | Transform event: DATA (just data), ENVELOPE (full event), RAW (base64) |

## Listener Strategies

| Strategy | Description | Completion |
|----------|-------------|------------|
| `ONE` | Single filter, single event | First match |
| `ANY` | Multiple filters, first match wins | First match |
| `ANY_UNTIL_EXPR` | Accumulate until expression is true | Expression match |
| `ANY_UNTIL_EVENT` | Accumulate until termination event | Termination event |
| `ALL` | One event per filter required | All filters matched |

## File Reference

| File | Responsibility |
|------|----------------|
| `ListenerService.kt` | Handle listen started and foreach completed events |
| `ListenerModel.kt` | Active listener entity with strategy and correlation |
| `ListenerEventModel.kt` | Matched CloudEvent storage |
| `ListenerStrategy.kt` | Strategy enum with factory from config |
| `ListenerRepository.kt` | Listener database operations |
| `ListenerEventRepository.kt` | Event storage operations |
| `ListenerQueryKey.kt` | Query structure for event matching |
| `outbox/ListenerCompletionOutbox.kt` | Resume workflow when listener completes |
| `outbox/ListenerForeachOutbox.kt` | Process foreach iterations in order |
| `outbox/ListenerScheduledTimeout.kt` | Handle listener timeouts |
| `cloudevents/CloudEventService.kt` | Match incoming CloudEvents to listeners |
| `cleaner/ListenerCleaner.kt` | Cleanup completed listeners |
| `ListenerConfig.kt` | Configuration for listener feature |

## How It Works

### Listen Execution Flow

```
┌─────────────┐    ListenStarted     ┌─────────────────┐
│   Workflow  │ ─────────────────▶   │ ListenerService │
│  Processor  │                      │ (create record) │
└─────────────┘                      └────────┬────────┘
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │   Waiting for   │
                                     │   CloudEvents   │
                                     └────────┬────────┘
                                              │
         CloudEvent arrives                   │
              │                               │
              ▼                               │
     ┌─────────────────┐                      │
     │ CloudEventService│ ◀───────────────────┘
     │ (match filters) │
     └────────┬────────┘
              │
              ▼
     ┌─────────────────┐
     │ Insert into     │
     │ listener_events │
     └────────┬────────┘
              │
    ┌─────────┴─────────┐
    │                   │
    ▼                   ▼
hasForeach=true    hasForeach=false
    │                   │
    ▼                   ▼
ListenerForeachOutbox  Check completion
(process sequentially) criteria
    │                   │
    └───────────────────┤
                        ▼
               ┌─────────────────┐
               │Set closed_at │
               └────────┬────────┘
                        │
                        ▼
               ┌─────────────────────┐
               │ListenerCompletionOutbox│
               │  (resume workflow)  │
               └─────────────────────┘
```

### Correlation Matching

1. **Expect expressions** evaluated against workflow context at listen start
2. **Correlation values** stored in listener record as JSON
3. **Incoming events** evaluated for matching correlation values
4. **Mode 2** (first-sets-baseline) - first event sets correlation for subsequent events

## Dependencies

| Depends On | Used By |
|------------|---------|
| `lemline-runner-common` | `lemline-runner` (event handlers) |
| `lemline-core` | - |

## Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| **Custom matching logic** | Modify `CloudEventService` |
| **New strategy** | Add to `ListenerStrategy` enum |
| **Custom readAs mode** | Extend `CloudEventService.applyReadAs()` |

## Database Tables

### `lemline_listeners`

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Derived from position + step |
| `instance_message` | TEXT | Workflow state for resumption |
| `strategy` | VARCHAR(20) | ONE/ANY/ANY_UNTIL_*/ALL |
| `timeout_at` | TIMESTAMP | Optional timeout |
| `correlation_values` | TEXT | JSON of expected correlations |
| `filters_count` | INT | Number of filters (for ALL) |
| `has_foreach` | BOOLEAN | Has foreach.do |
| `has_until` | BOOLEAN | Has until condition |
| `until_expression` | TEXT | JQ expression for until |
| `closed_at` | TIMESTAMP | Listener stopped accepting new events |
| `outbox_*` | Various | Outbox pattern fields |
| `cleanup_after` | TIMESTAMP | Eligible for deletion |

### `lemline_listener_events`

| Column | Type | Description |
|--------|------|-------------|
| `listener_id` | UUID | Reference to parent listener |
| `event_id` | VARCHAR(255) | CloudEvent ID |
| `filter_name` | VARCHAR(255) | Matched filter name |
| `cloud_event` | TEXT | Full CloudEvent JSON |
| `output` | TEXT | Foreach iteration output |
| `outbox_*` | Various | Outbox pattern fields |

## Complete event termination strategies

Cached listen tasks and `schedule.on` share `CachedEventConsumption`: one/any/all filters,
optional expression termination, and an optional event termination strategy. Listen routing
checks every termination filter, including `until.any: []` (any incoming event).

For `listen`, `until.all` progress is per listener instance and stored in `until_progress`.
Workers lock the listener with `FOR UPDATE`, evaluate the persisted progress, and commit
progress, main-event inserts, and closure in the same transaction. Failures roll back all
three; retries and other workers resume from database state. Foreach context updates take
the same listener lock. Termination events never bypass pending foreach processing.

Behavior changes from the former first-filter-only implementation:

- `until.any` accepts any of its filters, not just the first.
- `until.all` waits for all filters, in either order, with correlated partial progress.
- `until.any: []` terminates on the next event, even before any data is collected.
- An event that completes termination is excluded from the main output, even when it
  also matches the main filter. This aligns the durable runner with FullOrchestrator.
- Termination filters now honor extensions and correlation against instance context.

Expression termination and ordinary one/any/all collection retain their behavior.
DSL 1.0 schema validation still rejects another `until` inside an event termination strategy.

Migration V903 adds nullable listener progress on PostgreSQL, MySQL and H2. Existing
single-filter termination messages remain readable and retain their protobuf encoding.
New multi-filter/extension termination messages use an additive protobuf field; upgrade
workers before publishing workflows that require this field. Old workers cannot process it.
Already-running legacy listeners retain the single filter captured in their start message.

### CloudEvent redelivery

Migration V904 adds `lemline_listener_receipts`. Each listener claims a CloudEvent
by its exact `source + id` before evaluating termination or collecting events.
The listener row lock and the receipt, progress, collection and closure writes
share one transaction. A rollback releases the claim; a redelivery after commit
cannot advance the same listener, append another event, reevaluate its until
expression or enqueue another foreach iteration. Other listeners can still
consume the same CloudEvent.

The receipt primary key uses the listener ID and SHA-256 of the length-prefixed
source plus event ID to keep indexes bounded on all three databases. The complete
source and ID are stored and checked; a hash collision fails the transaction
instead of silently discarding an event. A delivery token restricts every batch
query to the receipts newly claimed by that transaction.

Receipts survive event cleanup and are removed by foreign-key cascade when their
listener is deleted. This costs one receipt per delivered event per listener and
serializes concurrent processing for a listener. Queue rows use their unique
`listener_id + event_index` identity, while completed output deduplication uses
`source + id` so different sources can reuse an ID.

All consuming workers must run the new code for this guarantee to hold. Receipts
are not reconstructed for deliveries committed before this upgrade, including
termination-only events that were never collected. Drain and acknowledge in-flight
deliveries before switching versions when an upgrade-time replay must be avoided.
