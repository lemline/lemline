---
title: "Wait for payments and collect order checks"
---

# Wait for payments and collect order checks

<web-summary>Wait for a payment event, collect order checks, and resume a workflow when its conditions are met.</web-summary>

A wait pauses an existing instance for a duration. A listen pauses it until matching
events arrive. The runner persists pending operations so it can resume an instance
after a restart.

Run the commands from the setup directory. With the JAR, replace `bin/lemline` with `java -jar lemline.jar`. In PowerShell, use `.\bin\lemline.exe`.

## Prepare the runner

Follow [the SQLite setup](lemline-tutorial-setup.md) to prepare a local runner that
serves the `examples` namespace. Leave the worker running. This exercise uses SQLite events and can run independently of the shop.

## Resume after a duration

Save the definition as `delayed-processing.yaml`. Its first task waits 30 seconds.
The next task returns a resumed state.

<code-block lang="yaml" src="workflows/reference/delayed-processing.yaml"/>

```bash
bin/lemline definition post -f delayed-processing.yaml
bin/lemline instance start examples delayed-processing
```

## Publish test events

Save this definition as `tutorial-publisher.yaml` and register it. It publishes the event type and data supplied as workflow input through the same SQLite messaging channel as the runner.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

## Resume when a payment arrives

Register the next definition and start it with an order ID in the workflow input.
Publish an event of type `com.example.payment.received` with the same `orderId` in
its payload. A payment for another order leaves this instance waiting.

<code-block lang="yaml" src="workflows/reference/listen-for-payment.yaml"/>

Save the definition as `listen-for-payment.yaml` and run:

```bash
bin/lemline definition post -f listen-for-payment.yaml
bin/lemline instance start examples listen-for-payment --input '{"orderId":"ORD-123"}'
```

Once the listener is waiting, publish these events in order:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"OTHER"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.received","data":{"orderId":"ORD-123"}}'
```

## Collect multiple results

Start an instance of this workflow and publish an inventory check and shipping quote.
The listener waits for both types before continuing.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

Save the definition as `listen-for-all-results.yaml`. Start the listener, then publish both events after it is waiting:

```bash
bin/lemline definition post -f listen-for-all-results.yaml
bin/lemline instance start examples listen-for-all-results
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.inventory.checked","data":{"stock":2}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.shipping.quoted","data":{"price":15}}'
```

After both events arrive, the instance completes with both results in `checks`. Continue with [Monitor the warehouse temperature](lemline-tutorial-iot.md).

See [Event Correlation](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#correlation) and [Timeouts](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#timeouts)
for matching and bounded waits.
