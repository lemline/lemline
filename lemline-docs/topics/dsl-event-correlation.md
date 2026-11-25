---
title: Event Correlation
---

<!-- Examples are validated -->


# Event Correlation

## Purpose

Event Correlation is a mechanism within the Open Workflow DSL used to filter and match incoming events based on **dynamic data values**, rather than just static attributes like event `type` or `source`. It allows a workflow instance to wait for or react to events that are specifically related to its current context or the data it is processing.

This is essential for scenarios such as:

*   Matching a specific `orderId` in an incoming event with the `orderId` being processed by the current workflow instance.
*   Ensuring a payment confirmation event corresponds to the correct `transactionId` stored in the workflow context.
*   Linking related events based on a shared `correlationId` present in their data payloads or attributes.

## How it Works

Correlation is configured within an [`Event Filter`](#event-filter-object-structure) using the `correlate` property. This property holds a map of user-defined correlation definitions.

For an incoming event to match the filter based on correlation, **all** defined correlations in the `correlate` map must succeed.

### Event Filter Object Structure

An `Event Filter` object is used in tasks like [Listen](dsl-task-listen.md) and potentially in event-driven `schedule` definitions. It contains:

*   **`with`** (Object, Required): Defines matching criteria based on standard event properties (like `type`, `source`, `subject`, `data`). Values can be static strings, regular expressions, or runtime expressions.
*   **`correlate`** (Map<String, [`Correlation`](#correlation-object-structure)>, Optional): A map where each key is a user-defined name for a correlation check (e.g., `matchOrderId`), and the value is a `Correlation` object defining the matching logic.

### Correlation Object Structure

Each `Correlation` object within the `correlate` map defines a single data-based matching rule:

*   **`from`** (String, Required): A [Runtime Expression](dsl-runtime-expressions.md) evaluated against the incoming event payload. For example, `${ .transactionId }`.
*   **`expect`** (String, Optional): Defines the value that the extracted `from` value must match.
    *   Can be a **static (constant) value** (e.g., `expect: "processed"`).
    *   Can be a [Runtime Expression](dsl-runtime-expressions.md) evaluated when the listen task starts. The workflow data is available through the expression arguments (`$input`, `$context`, `$workflow`), for example `expect: "${ $context.currentTransactionId }"`. An expression that cannot be evaluated, or that evaluates to `null`, faults the task.
    *   **If omitted:** The value extracted by the `from` expression from the *first* matching event encountered establishes the expectation for subsequent events within the same filter evaluation (this behavior is primarily relevant for specific `Listen` scenarios where multiple events might be processed sequentially against the same filter instance).

**Matching Logic:** An event satisfies a single correlation definition if the value produced by the `from` expression (evaluated on the event) is equal to the value produced by the `expect` expression (evaluated on the workflow context) or the static `expect` value.

## Usage Examples

### Match Dynamic Context Data

This workflow stores an order ID in context. A payment event matches only when its nested order reference has that value.

<code-block lang="yaml" src="workflows/dsl-event-correlation/correlate-payment-order.yaml"/>

### Match a Static Value

This workflow waits for a warehouse component to report the literal status `Ready`.

<code-block lang="yaml" src="workflows/dsl-event-correlation/correlate-ready-status.yaml"/>

### Require Multiple Correlations

This workflow resumes when a carrier confirmation matches both the shipment reference and the expected provider.

<code-block lang="yaml" src="workflows/dsl-event-correlation/correlate-shipment-provider.yaml"/>

### Publish the Events

Use this workflow to send each sample CloudEvent.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

## Relationship to Lifecycle Events

The DSL defines [lifecycle events](dsl-lifecycle-events.md) for the start and completion of event correlation.

> **Lemline**
>
> Lemline emits `workflow.correlation-started` when the listener begins waiting for a correlated
> event and `workflow.correlation-completed` when the correlated wait succeeds. Both events
> identify the same workflow instance.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run the publisher registration once from the setup directory.

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

Start `correlate-payment-order`, allow the listener to register, then send the nonmatching and matching events in order.

```bash
bin/lemline definition post -f correlate-payment-order.yaml
bin/lemline instance start examples correlate-payment-order --input '{"orderId":"O-150"}'
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.processed","data":{"transaction":{"orderRef":"O-151"}}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.processed","data":{"transaction":{"orderRef":"O-150"}}}'
```

Start `correlate-ready-status`, allow the listener to register, then send the nonmatching and matching events in order.

```bash
bin/lemline definition post -f correlate-ready-status.yaml
bin/lemline instance start examples correlate-ready-status
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.component.status","data":{"component":"sensor-1","status":"Starting"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.component.status","data":{"component":"sensor-1","status":"Ready"}}'
```

Start `correlate-shipment-provider`, allow the listener to register, then send the nonmatching and matching events in order.

```bash
bin/lemline definition post -f correlate-shipment-provider.yaml
bin/lemline instance start examples correlate-shipment-provider --input '{"shipment":"S-150","provider":"express"}'
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.carrier.confirmed","data":{"confirmation":{"shipment":"S-150","provider":"economy"}}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.carrier.confirmed","data":{"confirmation":{"shipment":"S-151","provider":"express"}}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.carrier.confirmed","data":{"confirmation":{"shipment":"S-150","provider":"express"}}}'
```

| Example | Expected result |
|---|---|
| `correlate-payment-order` | Returns the payment for `O-150` and ignores the payment for `O-151`. |
| `correlate-ready-status` | Returns the status update for `sensor-1` with `status: "Ready"`. |
| `correlate-shipment-provider` | Returns only the confirmation for `S-150` and provider `express`. |
| `tutorial-publisher` | Each publisher instance completes after emitting its event. |

### Inspect Correlation Lifecycle Events

With Node.js 22.13 or later, query `analytics.db` from the setup directory after the examples finish.

```bash
sleep 20
node --input-type=module <<'EOF'
import { DatabaseSync } from 'node:sqlite';

const db = new DatabaseSync('analytics.db', { readOnly: true });
const rows = db.prepare(`
  SELECT lemline_workflow_name AS workflow,
         lemline_workflow_id AS instance,
         json_extract(payload, '$.type') AS event
  FROM lemline_lifecycle_events
  WHERE lemline_workflow_name IN (
    'correlate-payment-order', 'correlate-ready-status', 'correlate-shipment-provider'
  )
    AND json_extract(payload, '$.data.type') IN (
      'workflowCorrelationStarted', 'workflowCorrelationCompleted'
    )
  ORDER BY workflow, instance, id
`).all();
for (const row of rows) console.log(JSON.stringify(row));
db.close();
EOF
```

Each listener instance has a pair of events with the same instance ID, one ending in
`workflow.correlation-started.v1` and one ending in `workflow.correlation-completed.v1`.
