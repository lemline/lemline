---
title: Event Correlation
---

# Event Correlation

## Purpose

Event Correlation is a mechanism within the Serverless Workflow DSL used to filter and match incoming events based on **dynamic data values**, rather than just static attributes like event `type` or `source`. It allows a workflow instance to wait for or react to events that are specifically related to its current context or the data it is processing.

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

*   **`from`** (String, Required): A [Runtime Expression](dsl-runtime-expressions.md) evaluated against the **incoming event** (its context attributes and `data` payload) to extract a value. For example, `${ .data.transactionId }`.
*   **`expect`** (String, Optional): Defines the value that the extracted `from` value must match.
    *   Can be a **static (constant) value** (e.g., `expect: "processed"`).
    *   Can be a [Runtime Expression](dsl-runtime-expressions.md) evaluated against the **workflow's context** (`$context`, `$secrets`, etc.) *at the time the filter is checked* (e.g., `expect: "${ $context.currentTransactionId }"`).
    *   **If omitted:** The value extracted by the `from` expression from the *first* matching event encountered establishes the expectation for subsequent events within the same filter evaluation (this behavior is primarily relevant for specific `Listen` scenarios where multiple events might be processed sequentially against the same filter instance).

**Matching Logic:** An event satisfies a single correlation definition if the value produced by the `from` expression (evaluated on the event) is equal to the value produced by the `expect` expression (evaluated on the workflow context) or the static `expect` value.

## Usage Examples

### Example 1: Matching Dynamic Context Data (within Listen Task)

Imagine a workflow processing an order needs to wait for a specific payment confirmation event based on the order ID stored in the workflow's context.

```yaml
document:
  dsl: '1.0.0'
  namespace: order_processing
  name: wait-for-payment
  version: '1.0.0'
do:
  - storeOrderId:
      set:
        # Assume input contains orderId, store it in context
        orderId: "${ .initialOrder.id }"
      export:
         as: "${ $context + { currentOrderId: .orderId } }"
      then: waitForPaymentEvent
  - waitForPaymentEvent:
      listen:
        to:
          one: # Wait for one specific event
            eventFilter:
              # Basic filtering on event type
              with:
                type: com.payment.processed.v1
              # Correlation based on data
              correlate:
                # Name this correlation check 'matchOrderId'
                matchOrderId:
                  # Extract 'transaction.orderRef' from the incoming event's data
                  from: "${ .data.transaction.orderRef }"
                  # Expect it to match the 'currentOrderId' stored in workflow context
                  expect: "${ $context.currentOrderId }"
        # Optional: Timeout if the event doesn't arrive
        timeout: { minutes: 30 }
      # Output contains the received event if successful
      then: processPaymentConfirmation
  - processPaymentConfirmation:
      # ... task input is the matched payment event ...
```

In this example, the `waitForPaymentEvent` task will only proceed if it receives an event with `type: com.payment.processed.v1` AND the value of `data.transaction.orderRef` inside that event matches the `currentOrderId` currently stored in the workflow's context.

### Example 2: Matching a Static Value

Suppose a workflow needs to wait for a system status update event indicating that a specific component (`component-abc`) has become 'Ready'.

```yaml
document:
  dsl: '1.0.0'
  namespace: system_monitor
  name: wait-for-component-ready
  version: '1.0.0'
do:
  - waitForComponentReady:
      listen:
        to:
          one:
            eventFilter:
              with:
                type: com.system.status.update.v1
                source: /systems/monitoring/component-abc # Filter by source
              correlate:
                matchComponentStatus:
                  # Extract the status field from the event data
                  from: "${ .data.status }"
                  # Expect the status to be the exact string 'Ready'
                  expect: "Ready"
      then: componentIsReady
  - componentIsReady:
      # ... task to execute now that the component is ready ...
```

Here, the correlation `matchComponentStatus` checks if the `status` field within the event's data payload is exactly equal to the static string `"Ready"`.

### Example 3: Requiring Multiple Correlations

Consider a scenario where a workflow orchestrates a travel booking and needs to wait for a confirmation event that matches both the specific `bookingId` and the `provider` ('AcmeAir') involved.

```yaml
document:
  dsl: '1.0.0'
  namespace: travel_booking
  name: wait-for-flight-confirmation
  version: '1.0.0'
do:
  - storeBookingInfo:
      set:
        bookingId: "${ .flightRequest.id }"
        provider: "AcmeAir"
      export:
        as: "${ $context + { currentBookingId: .bookingId, currentProvider: .provider } }"
      then: waitForConfirmation
  - waitForConfirmation:
      listen:
        to:
          one:
            eventFilter:
              with:
                type: com.travel.confirmation.flight.v1
              # Requires BOTH correlations to succeed
              correlate:
                matchBookingId:
                  from: "${ .data.confirmation.bookingRef }"
                  expect: "${ $context.currentBookingId }"
                matchProvider:
                  from: "${ .data.confirmation.providerName }"
                  expect: "${ $context.currentProvider }" # Or could be expect: "AcmeAir"
        timeout: { hours: 1 }
      then: processFlightConfirmation
  - processFlightConfirmation:
      # ... task input is the confirmation event matching both criteria ...
```

In this case, the `listen` task will only be satisfied by an event of the correct type where `data.confirmation.bookingRef` matches the `$context.currentBookingId` **AND** `data.confirmation.providerName` matches `$context.currentProvider`.

## Relationship to Lifecycle Events

When a workflow successfully completes an event correlation (e.g., a `Listen` task receives all required correlated events), the runtime typically emits a `Workflow Correlation Completed Event`. This standard lifecycle event includes a `correlationKeys` map containing the names (e.g., `matchOrderId` from the example above) and the resolved values of the correlations that were successfully matched. This provides visibility into *which* specific data values led to the correlation match. 