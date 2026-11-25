---
title: How to execute tasks in parallel (fork)
---

# How to execute tasks in parallel

Use `fork.branches` to run independent branches concurrently. Each branch has its own
task input. By default, a fork waits for all branches.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The examples
call the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop); start it from the root of
the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Save this definition as `parallel-order-checks.yaml`:

<code-block lang="yaml" src="workflows/reference/parallel-order-checks.yaml"/>

```bash
bin/lemline definition post -f parallel-order-checks.yaml
bin/lemline instance start examples parallel-order-checks --input '{"orderId":"QUOTE-1"}'
```

The fork returns the results of its branches in the order they are listed:
`{"checks":[{"available":100},{"orderId":"QUOTE-1","carrier":"express","attempts":1,"price":9}]}`.
For a complete checkout, [compare the two carriers](lemline-tutorial-order-processing.md#ship-a-paid-order). Each branch returns its own price and `min_by(.price)` selects the carrier after both branches finish.

## Handle errors around a fork

Use a surrounding `try` task when the workflow needs a fallback for the whole group.
Place a separate `try` inside a branch when that branch can recover independently.

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

The `inventoryFailures` input makes the shop's stock endpoint answer HTTP 503 that many times. Save the
definition as `parallel-checks-with-fallback.yaml` and run it with a working and a failing inventory:

```bash
bin/lemline definition post -f parallel-checks-with-fallback.yaml
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"QUOTE-2"}'
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"QUOTE-3","inventoryFailures":1}'
```

The first instance returns both branch results. In the second, the inventory branch fails, the fork fails with it,
and the `catch` returns `{"status":"needs-review"}`. The shipping branch still ran: the shop lists an express quote for `QUOTE-3`.

## Wait for outcomes published as events

A `listen` task can collect events produced by independent services. In this example,
`all` requires one match for each filter and `timeout.after` bounds the wait.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

This example needs the publisher from the tutorial. Save it as `tutorial-publisher.yaml`:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

Save the listener as `listen-for-all-results.yaml`, start it, then publish both events:

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f listen-for-all-results.yaml
bin/lemline instance start examples listen-for-all-results
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.inventory.checked","data":{"stock":2}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.shipping.quoted","data":{"price":15}}'
```

After the second event the listener completes with `{"checks":[{"stock":2},{"price":15}]}`. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

Use a [For Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#for) to iterate over a collection at runtime. See
[Fan-Out and Fan-In](lemline-explain-fan-in.md) for the difference between iteration,
forking tasks and waiting for events.
