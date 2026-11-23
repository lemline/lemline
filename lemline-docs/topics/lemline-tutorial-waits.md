---
title: "Tutorial: Waits, Fan-In, and Timers"
---

# Tutorial: Waits, Fan-In, and Timers

A wait pauses an existing instance for a duration. A listen pauses it until matching
events arrive. The runner persists pending operations so it can resume an instance
after a restart.

## Prepare the runner

Follow [Getting Started](lemline-getting-started.md) to prepare a local runner that
serves the `examples` namespace.

## Resume after a duration

Save the definition as `delayed-processing.yaml`. Its first task waits 30 seconds.
The next task returns a resumed state.

<code-block lang="yaml" src="workflows/reference/delayed-processing.yaml"/>

```bash
lemline definition post -f delayed-processing.yaml
lemline instance start examples delayed-processing
```

## Resume when a payment arrives

Register the next definition and start it with an order ID in the workflow input.
Publish an event of type `com.example.payment.received` with the same `orderId` in
its payload. A payment for another order leaves this instance waiting.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

## Collect multiple results

Start an instance of this workflow and publish an inventory check and shipping quote.
The listener waits for both types before continuing.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

See [Event Correlation](dsl-event-correlation.md) and [Timeouts](dsl-timeouts.md)
for matching and bounded waits.
