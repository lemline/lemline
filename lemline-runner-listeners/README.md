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
│  ListenerService            ← Business logic for listen events │
│  ├── handleListenStarted()  ← Create listener record           │
│  └── handleListenForEachCompleted() ← Mark iteration done      │
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
│  ├── ListenerCompletionOutbox ← Resume when completed_at is set│
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
               │Set completed_at │
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
| `completed_at` | TIMESTAMP | Listener stopped collecting events |
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
