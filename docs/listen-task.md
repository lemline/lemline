# Listen Task

The `listen` task provides a mechanism for workflows to await and react to external events, enabling event-driven behavior within workflow systems.

## Overview

When a workflow encounters a `listen` task, it enters a `WAITING` state until the specified event conditions are satisfied. Once the conditions are met, the workflow resumes execution with the received event(s) as the task output.

## Basic Syntax

```yaml
do:
  - taskName:
      listen:
        to:
          <consumption-strategy>
        read: data | envelope | raw  # optional, defaults to 'data'
      foreach:  # optional
        item: <variable-name>
        do:
          - <nested-tasks>
```

## Event Consumption Strategies

The `listen.to` property defines which events the task waits for. Three strategies are available:

### 1. `one` - Wait for a Single Event

Waits for exactly one event matching the specified filter.

```yaml
listen:
  to:
    one:
      with:
        type: com.example.order.created
```

**Completion**: When one matching event is received.
**Output**: Array containing one element (the event data).

### 2. `any` - Wait for Any Matching Event

Waits for the first event matching any of the specified filters.

```yaml
listen:
  to:
    any:
      - with:
          type: com.example.order.created
      - with:
          type: com.example.order.updated
```

**Completion**: When the first event matching any filter is received.
**Output**: Array containing one element (the first matching event data).

#### Special Case: Empty `any: []`

An empty array means "listen to any event regardless of type":

```yaml
listen:
  to:
    any: []
```

This is a wildcard that completes on the very first event received, regardless of its type or content.

### 3. `all` - Wait for All Specified Events

Waits for one event matching each specified filter. Since a CloudEvent can only have one `type`, this strategy requires multiple distinct events.

```yaml
listen:
  to:
    all:
      - with:
          type: com.example.payment.received
      - with:
          type: com.example.inventory.reserved
```

**Completion**: When one event per filter has been received (order doesn't matter).
**Output**: Array containing one element per filter (e.g., 2 filters = 2 events in output).

## Event Filtering

Each filter uses the `with` property to match CloudEvent attributes:

```yaml
with:
  type: <string>              # Exact string match only
  source: <uri|expression>    # URI or runtime expression
  subject: <string>           # Exact string match only
  id: <string>                # Exact string match only
  datacontenttype: <string>   # Exact string match only
  dataschema: <uri|expression> # URI or runtime expression
  time: <datetime|expression> # Datetime or runtime expression
  data: <expression>          # Runtime expression (see below)
```

### Expression Support by Property

Not all `with` properties support runtime expressions:

| Property | Supports Expression? | Evaluated Against |
|----------|---------------------|-------------------|
| `id` | ❌ No | Exact string match |
| `type` | ❌ No | Exact string match |
| `subject` | ❌ No | Exact string match |
| `datacontenttype` | ❌ No | Exact string match |
| `source` | ✅ Yes | The `source` URI value |
| `dataschema` | ✅ Yes | The `dataschema` URI value |
| `time` | ✅ Yes | The `time` timestamp value |
| `data` | ✅ Yes | The event's **data payload** |

### Literal vs Expression Behavior

- **Literal value**: Used for **equality matching** against the CloudEvent attribute
  ```yaml
  with:
    source: https://example.com/orders  # Matches if event.source == this URI
    time: "2024-01-15T10:00:00Z"        # Matches if event.time == this timestamp
  ```

- **Runtime expression**: Must return a **boolean** for filtering
  ```yaml
  with:
    source: ${ . | startswith("https://example.com") }  # Expression on source URI
    time: ${ . > "2024-01-01T00:00:00Z" }               # Expression on timestamp
    data: ${ .temperature > 38 }                        # Expression on event data
  ```

> **Important: Expression Context**
>
> - For `source`, `dataschema`, `time`: the expression is evaluated against that **single attribute value** (`.` refers to the source URI string, the dataschema URI, or the timestamp - not the event data)
> - For `data`: the expression is evaluated against the **full event data payload** (`.temperature` accesses the `temperature` field in the data)
> - **None** of these expressions have access to workflow context (`$input`, `$context`, `$workflow`, `$task`)
>
> To filter events based on workflow-specific data, use the `correlate` property (see [Event Correlation](#event-correlation)).

### Filtering on Event Data

The `data` property accepts a runtime expression that is evaluated against the event's data payload. The expression must return a boolean:

```yaml
listen:
  to:
    one:
      with:
        type: com.hospital.vitals.temperature
        data: ${ .temperature > 38 }
```

In this example:
- `.temperature` refers to the `temperature` field in the event's data payload
- Only events where `data.temperature > 38` will match

Multiple conditions can be combined:

```yaml
listen:
  to:
    any:
      - with:
          type: com.hospital.vitals.temperature
          data: ${ .temperature > 38 }
      - with:
          type: com.hospital.vitals.bpm
          data: ${ .bpm < 60 or .bpm > 100 }
```

## Event Correlation

The `correlate` property enables instance-specific event routing. It establishes a link between events and workflow data, ensuring that events are delivered only to the workflow instance that expects them.

### Why Correlation Matters

Without correlation, events are broadcast to all workflow instances listening for the same event type:

```
Instance A (orderId: "123"): listening for type=order.shipped
Instance B (orderId: "456"): listening for type=order.shipped

Event arrives: type=order.shipped, data.orderId="456"

Without correlation:
→ Instance A receives the event ❌ (wrong order!)
→ Instance B receives the event ✓

With correlation:
→ Instance A ignores the event ✓ (orderId doesn't match)
→ Instance B receives the event ✓ (orderId matches)
```

### Syntax

```yaml
listen:
  to:
    one:
      with:
        type: <event-type>
      correlate:
        <correlation-key>:
          from: <expression>    # Required: extract value from event
          expect: <expression>  # Optional: expected value from workflow context
```

### Properties

| Property | Required | Description | Expression Context |
|----------|----------|-------------|-------------------|
| `from` | Yes | A runtime expression to extract the correlation value from the incoming event | **CloudEvent payload** (`.` = event data) |
| `expect` | No | A runtime expression or constant defining the expected value. If not set, the first extracted value becomes the expectation. | **Workflow context** (`$input`, `$context`, etc.) |

### Expression Context: The Key Difference

This is the critical distinction between `with` filters and `correlate`:

| Property | Evaluated Against | Can Access |
|----------|-------------------|------------|
| `with.type`, `with.source`, `with.data`, etc. | CloudEvent payload | `.` (event data only) |
| `correlate.<key>.from` | CloudEvent payload | `.` (event data only) |
| `correlate.<key>.expect` | **Workflow data** | `$input`, `$context`, `$workflow`, `$task` |

**`correlate.expect` is the ONLY place** where you can reference workflow-specific data to filter events.

```yaml
correlate:
  orderId:
    from: '${ .orderId }'         # Evaluated against EVENT → extracts event.data.orderId
    expect: '${ $input.orderId }' # Evaluated against WORKFLOW → compares to workflow input
```

### Examples

#### Basic Correlation

Route `order.shipped` events to the workflow instance with the matching `orderId`:

```yaml
do:
  - waitForShipment:
      listen:
        to:
          one:
            with:
              type: order.shipped
            correlate:
              orderId:
                from: '${ .orderId }'           # Extract orderId from event data
                expect: '${ $input.orderId }'   # Match against workflow input
```

**Behavior**:
- When an `order.shipped` event arrives, extract `.orderId` from the event data
- Compare it to `$input.orderId` (the workflow's input)
- Only deliver the event if the values match

#### Multiple Correlation Keys

Correlate on multiple attributes:

```yaml
listen:
  to:
    one:
      with:
        type: payment.completed
      correlate:
        orderId:
          from: '${ .orderId }'
          expect: '${ $context.orderId }'
        customerId:
          from: '${ .customerId }'
          expect: '${ $context.customerId }'
```

All correlation keys must match for the event to be delivered.

#### Auto-Correlation (No `expect`)

When `expect` is omitted, the first received value becomes the expectation:

```yaml
listen:
  to:
    all:
      - with:
          type: order.created
        correlate:
          orderId:
            from: '${ .orderId }'
            # No expect: first event's orderId becomes the correlation value
      - with:
          type: order.paid
        correlate:
          orderId:
            from: '${ .orderId }'
            # Must match the orderId from the first event
```

This is useful when the workflow doesn't know the correlation value upfront but needs to ensure subsequent events relate to the same entity.

### Implementation Status

> **Warning**: The `correlate` keyword is **NOT YET IMPLEMENTED** in this SDK.
>
> The type definitions exist (generated from the schema), but the runtime ignores the `correlate` property. Currently, all workflow instances listening for the same event type receive all matching events (broadcast semantics).
>
> Contributions to implement this feature are welcome.

## The `until` Clause (Accumulation Mode)

By default, `any` completes on the first matching event. The `until` clause changes this behavior to **accumulate events** until a termination condition is met.

### Terminate on Expression

```yaml
listen:
  to:
    any:
      - with:
          type: com.hospital.vitals.temperature
    until: . | any(.temperature > 38)
```

Events are accumulated until the expression evaluates to `true` on the collected array.

### Terminate on Event

```yaml
listen:
  to:
    any:
      - with:
          type: com.hospital.vitals.temperature
      - with:
          type: com.hospital.vitals.bpm
    until:
      one:
        with:
          type: com.hospital.patient.checked-out
```

**Behavior**:
1. Accumulate all matching temperature and bpm events
2. When `patient.checked-out` event arrives, stop listening
3. Return all accumulated events

**Output**: Array of all events received before the termination event.

```
Timeline example:
t=0   listen starts
t=2   temperature event (temp=37) → added to array
t=5   bpm event (bpm=120) → added to array
t=7   temperature event (temp=39) → added to array
t=10  patient.checked-out event → STOP

Output: [
  { "temperature": 37, ... },
  { "bpm": 120, ... },
  { "temperature": 39, ... }
]
```

## Output Format (`read` property)

The `read` property controls how event data is extracted:

| Value | Description | Output Content |
|-------|-------------|----------------|
| `data` (default) | Extract event payload only | `event.getData()` |
| `envelope` | Include full CloudEvent | `{ type, source, id, data, ... }` |
| `raw` | Raw event processing | Implementation-specific |

```yaml
listen:
  to:
    one:
      with:
        type: com.example.order.created
  read: envelope
```

## Processing Events with `foreach`

The `foreach` property allows executing nested tasks for each event as it arrives:

```yaml
listen:
  to:
    any:
      - with:
          type: com.hospital.vitals.temperature
    until: . | any(.temperature > 38)
  foreach:
    item: event
    at: index      # optional: current index
    do:
      - logMeasurement:
          set:
            temperature: ${ $event.temperature }
            timestamp: ${ now }
```

**Behavior**:
- `item`: Variable name bound to the current event
- `at`: Variable name bound to the current index (0-based)
- `do`: Tasks executed for each event

The nested tasks are executed **as events arrive**, not after all events are collected.

## Multiple Workflow Instances

Events are delivered using **broadcast semantics**:

- When multiple workflow instances are listening for the same event type, **all matching instances receive the event**
- This is pub/sub behavior, not message queue behavior
- Each instance maintains its own independent event registration

```
Instance A: listening for type=order.created
Instance B: listening for type=order.created

Event arrives: type=order.created

→ Both Instance A and Instance B receive the event
→ Both instances may complete their listen tasks
```

## Complete Examples

### Example 1: Wait for Order Confirmation (with Correlation)

```yaml
do:
  - waitForConfirmation:
      listen:
        to:
          one:
            with:
              type: com.store.order.confirmed
            correlate:
              orderId:
                from: '${ .orderId }'
                expect: '${ $input.orderId }'
```

> **Note**: This example uses `correlate` which is not yet implemented in the SDK.

### Example 2: Wait for Payment OR Cancellation

```yaml
do:
  - waitForPaymentOrCancel:
      listen:
        to:
          any:
            - with:
                type: com.store.payment.received
            - with:
                type: com.store.order.cancelled
```

### Example 3: Wait for All Required Approvals

```yaml
do:
  - waitForApprovals:
      listen:
        to:
          all:
            - with:
                type: com.company.approval.manager
            - with:
                type: com.company.approval.finance
            - with:
                type: com.company.approval.legal
```

### Example 4: Collect Sensor Data Until Threshold

```yaml
do:
  - collectReadings:
      listen:
        to:
          any:
            - with:
                type: com.iot.sensor.reading
          until: . | any(.value > 100)
      foreach:
        item: reading
        do:
          - store:
              call: http
              with:
                method: POST
                endpoint: https://api.example.com/readings
                body: ${ $reading }
```

### Example 5: Monitor Until Shift Ends

```yaml
do:
  - monitorPatient:
      listen:
        to:
          any:
            - with:
                type: com.hospital.vitals.temperature
                data: ${ .temperature > 38 }
            - with:
                type: com.hospital.vitals.heartrate
                data: ${ .bpm < 60 or .bpm > 100 }
          until:
            one:
              with:
                type: com.hospital.shift.ended
      foreach:
        item: alert
        do:
          - notifyDoctor:
              call: http
              with:
                method: POST
                endpoint: https://api.hospital.com/alerts
                body:
                  vital: ${ $alert }
```

## Summary Table

| Strategy | Filters | Completion Condition | Output Size |
|----------|---------|---------------------|-------------|
| `one` | 1 filter | 1 matching event | 1 event |
| `any` (no until) | N filters | First match from any filter | 1 event |
| `any` + `until` | N filters | Until condition met | 0 to N events |
| `all` | N filters | 1 match per filter | N events |
| `any: []` | None (wildcard) | First event (any type) | 1 event |

## Event Infrastructure

The SDK provides a pluggable event infrastructure:

- **Default**: `InMemoryEvents` - in-process pub/sub (events published via `emit` task)
- **Custom**: Implement `EventConsumer` and `EventPublisher` interfaces for external brokers (Kafka, RabbitMQ, etc.)

```java
WorkflowApplication.builder()
    .withEventConsumer(new KafkaEventConsumer(...))
    .withEventPublisher(new KafkaEventPublisher(...))
    .build();
```

Events are delivered asynchronously using `CompletableFuture`, ensuring non-blocking workflow execution.
