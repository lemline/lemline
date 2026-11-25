---
title: Workflow Definition Examples
---

# Workflow Definition Examples

These complete examples combine the constructs described in [Workflow Definition Structure](dsl-workflow-definition.md).

## Sequential Task Execution

The workflow checks that the customer exists, reserves the order’s items, and then charges the order.
Its `use.functions` entries define named operations that receive the arguments passed by each call.

<code-block lang="yaml" src="workflows/dsl-workflow-examples/process-new-order.yaml"/>

## Event-Driven Customer Communication

A customer signup event starts the workflow. The contact preference selects one notification path.
The shop records each notification in its inbox.

<code-block lang="yaml" src="workflows/dsl-workflow-examples/customer-communication.yaml"/>

Start the publisher to send the signup events.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

## Parallel Execution and Error Handling

This example runs the stock and shipping checks in parallel. The surrounding catch returns a manual review
result if either check fails.

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

## Time-Based Scheduled Workflow

The scheduled workflow records a stock audit at the start of each minute. It creates a new instance on each occurrence.

<code-block lang="yaml" src="workflows/dsl-workflow-examples/scheduled-shop-audit.yaml"/>

## Input and Output Transformation

The workflow reshapes incoming customer and item fields and returns the customer ID with the number of items.

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run all registration commands in a fresh setup directory.

```bash
bin/lemline definition post -f process-new-order.yaml
bin/lemline definition post -f customer-communication.yaml
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f parallel-checks-with-fallback.yaml
bin/lemline definition post -f transform-workflow-data.yaml
bin/lemline definition post -f scheduled-shop-audit.yaml
```

> **Lemline**
>
> Once registration finishes, stop the setup worker and restart it in its terminal so it subscribes to the signup filter.

```bash
bin/lemline listen --debug
```

In the second terminal, start the order and transformation workflows and run the parallel checks both with and without a shop failure.

```bash
bin/lemline instance start examples process-new-order --input '{"orderId":"O-190","customerId":"C-100","items":[{"id":"SKU-1","quantity":2}],"paymentToken":"approved"}'
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"O-191"}'
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"O-192","inventoryFailures":1}'
bin/lemline instance start examples transform-workflow-data --input '{"user":{"id":"C-100"},"payload":{"items":["SKU-1","SKU-2"]}}'
```

Publish a signup event for each contact preference.

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.customer.signup.v1","data":{"reference":"REG-190","contactMethod":"email","email":"ada@example.com"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.customer.signup.v1","data":{"reference":"REG-191","contactMethod":"sms","phone":"+15550100191"}}'
```

> **Lemline**
>
> Query the shop's inbox and audit entries after the first cron occurrence runs.

```bash
bin/lemline instance start examples scheduled-shop-audit --zone UTC
sleep 75
curl -fsS http://127.0.0.1:8787/state
```

| Example | Expected result |
|---|---|
| `process-new-order` | Returns `{"orderId":"O-190","paid":true,"amount":50}`. |
| `parallel-checks-with-fallback` for `O-191` | Returns the stock and an express quote priced at `9`. |
| `parallel-checks-with-fallback` for `O-192` | Returns `{"status":"needs-review"}` after the inventory failure. |
| `transform-workflow-data` | Returns `{"customerId":"C-100","itemCount":2}`. |
| `tutorial-publisher` | Each publisher instance completes after emitting its signup event. |
| `customer-communication` | The shop inbox records `Welcome by email` for `REG-190` and `Welcome by SMS` for `REG-191`. |
| `scheduled-shop-audit` | The first scheduled instance returns `{"report":"stock-recorded"}` and records `Available units: 98` in the shop audit. |

After checking the results, stop the worker with Ctrl+C before stopping the shop. This pauses the cron schedule,
which remains in this example’s SQLite project. Start the shop again before restarting this worker.

Run the shutdown command from the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
