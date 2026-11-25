# Listen

A `listen` task pauses the instance until it consumes matching CloudEvents or reaches its timeout.
The conditions in `listen.to` define which events can resume the task.

## Filter on Event Data

The first workflow waits for a stock alert. A stock update resumes it only when fewer than five units remain.

<code-block lang="yaml" src="workflows/dsl-task-listen/listen-low-stock.yaml"/>

## Choose a Consumption Strategy

### Any

Use `any` when one of several signals is enough. Here either an approval or a rejection completes the task.

<code-block lang="yaml" src="workflows/dsl-task-listen/listen-any-decision.yaml"/>

### All

Use `all` when each listed event filter must match. Here the inventory and shipping checks must both arrive.

<code-block lang="yaml" src="workflows/dsl-task-listen/listen-all-checks.yaml"/>

### One

Use `one` for a single filter. Attributes in the filter can match a type and source together. For data-based
identity matching, see [Event Correlation](dsl-event-correlation.md).

<code-block lang="yaml" src="workflows/dsl-task-listen/listen-one-verification.yaml"/>

### Until

With `any`, an `until` filter lets a separate event stop collection. This workflow collects updates until
an order completion event arrives. The completion event ends the collection and does not appear in its output.

<code-block lang="yaml" src="workflows/reference/listen-until-complete.yaml"/>

## Read and Process the Events

`listen.read` selects the data payload (the default), the full `envelope`, or the `raw` received event.
The task returns a list in consumption order. A `foreach` iterator can process and transform each item before it
joins that list. The task’s `timeout` can limit how long the instance waits. See [Timeouts](dsl-timeouts.md).

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

After the required events are consumed, control follows the task’s `then` directive.
If the timeout arrives first, the task faults with a timeout error.

## Publish Sample Events

Use the publisher below to send events from a second instance.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run the publisher registration once from the setup directory.

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

Start `listen-low-stock` and allow the listener to register before sending the first event.

```bash
bin/lemline definition post -f listen-low-stock.yaml
bin/lemline instance start examples listen-low-stock
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.stock.changed","data":{"sku":"SKU-1","available":20}}'
sleep 6
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.stock.changed","data":{"sku":"SKU-1","available":3}}'
```

Start `listen-any-decision` and allow the listener to register before sending the first event.

```bash
bin/lemline definition post -f listen-any-decision.yaml
bin/lemline instance start examples listen-any-decision
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.rejected","data":{"decision":"rejected"}}'
```

Start `listen-all-checks` and allow the listener to register before sending the first event.

```bash
bin/lemline definition post -f listen-all-checks.yaml
bin/lemline instance start examples listen-all-checks
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"inventory.checked","data":{"check":"inventory"}}'
sleep 6
bin/lemline instance start examples tutorial-publisher --input '{"type":"shipping.ready","data":{"check":"shipping"}}'
```

Start `listen-one-verification` and allow the listener to register before sending the first event.

```bash
bin/lemline definition post -f listen-one-verification.yaml
bin/lemline instance start examples listen-one-verification
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"user.verified","data":{"customerId":"C-100"}}'
```

Start `listen-until-complete` and allow the listener to register before sending the first event.

```bash
bin/lemline definition post -f listen-until-complete.yaml
bin/lemline instance start examples listen-until-complete
sleep 12
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.updated","data":{"status":"paid"}}'
sleep 6
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.completed","data":{"status":"complete"}}'
```

| Example | Expected result |
|---|---|
| `listen-low-stock` | Returns only the update with `available: 3`. |
| `listen-any-decision` | Finishes on the rejection and returns `[{"decision":"rejected"}]`. |
| `listen-all-checks` | Returns both checks in the order sent. |
| `listen-one-verification` | Returns the matching verification data. |
| `listen-until-complete` | Returns the paid update alone. The completion event stops the listener. |
| `tutorial-publisher` | Each publisher instance completes after emitting its event. |
