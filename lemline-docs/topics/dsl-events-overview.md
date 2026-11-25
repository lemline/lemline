---
title: Event Tasks
---

# Event Tasks

Event tasks enable workflows to interact with event-driven systems, allowing workflows to listen for external events and emit events to other systems, facilitating asynchronous and reactive patterns in your applications.

## Common Use Cases

Leveraging events in your workflows unlocks several powerful patterns:

* **Waiting for External Triggers:** A workflow can start or resume based on an external event, such as a new file
  arriving in storage, a message landing on a queue, or a webhook notification. (Often implemented using `Listen` or
  platform-specific triggers).
* **Reacting to System Changes:** Workflows can listen for events indicating changes in other systems (e.g., inventory
  updates, user profile changes) and trigger appropriate actions.
* **Asynchronous Task Completion:** A workflow can initiate a long-running operation via a call task and then use
  `Listen` to wait for a completion event from that operation, rather than blocking synchronously.
* **Inter-Workflow Communication:** One workflow can `Emit` an event that triggers or provides data to another workflow
  instance via `Listen`.
* **Saga Pattern / Compensating Transactions:** Events can signal the success or failure of steps in a distributed
  transaction, allowing other services or workflows to react and perform compensating actions if necessary.
* **Decoupled Integration:** Services can communicate via events without needing direct knowledge of each other,
  promoting loose coupling and independent evolution.


## Available Event Tasks

| Task | Purpose |
|------|---------|
| [Listen](dsl-task-listen.md) | Wait for events from external sources and optionally filter them |
| [Emit](dsl-task-emit.md) | Send events to external systems or trigger other workflows |

## When to Use Event Tasks

- Use **Listen** when you need to:
  - Resume an existing workflow instance from an external event
  - Wait for specific conditions signaled by events
  - Implement event-driven patterns like event sourcing
  - Build reactive workflows that respond to system changes
  - Coordinate long-running processes across distributed systems

- Use **Emit** when you need to:
  - Notify other systems about workflow state changes
  - Trigger parallel workflows or microservices
  - Implement pub/sub patterns for loose coupling
  - Broadcast completion or progress updates
  - Signal transitions in business processes

Use `schedule.on` in the workflow definition when an event should start a new instance.

## Examples

### Order Processing with Event Handling

An order event starts the workflow. It waits for a payment event for the same order, then publishes the
payment status. If the event never arrives, it looks for the order’s charge in the shop and falls back
to manual review when there is no charge.

`schedule.on` gives the workflow an array of event payloads. `input.from` passes the first payload to
the tasks, and `$workflow.input[0]` still reads that payload when a later task has different input.

<code-block lang="yaml" src="workflows/dsl-events-overview/event-driven-order-processing.yaml"/>

### Event-Based Microservice Coordination

A registration event starts two independent operations. The shop creates the customer and records a
welcome notification. The workflow publishes a completion event after both branches finish.

<code-block lang="yaml" src="workflows/dsl-events-overview/coordinate-onboarding.yaml"/>

### Send the Sample Events

Use this publisher to send the order, payment, and registration events.

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run the three registration commands in the setup directory.

```bash
bin/lemline definition post -f event-driven-order-processing.yaml
bin/lemline definition post -f coordinate-onboarding.yaml
bin/lemline definition post -f tutorial-publisher.yaml
```

> **Lemline**
>
> Register the definitions before restarting the worker so it subscribes to the new `schedule.on` filters. Stop it with Ctrl+C and run the restart command in its terminal.

```bash
bin/lemline listen --debug
```

In the second terminal, publish the first order and wait for the worker to start its payment listener before sending the matching payment event. Send only an order event for the second order. Publish a registration to start the onboarding workflow.

```bash
sleep 5
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.created","data":{"orderId":"O-162"}}'
sleep 20
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.payment.processed","data":{"orderId":"O-162","status":"approved"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.order.created","data":{"orderId":"O-163"}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.user.registered","data":{"registrationId":"REG-160","name":"Hedy Lamarr","email":"hedy@example.com"}}'
sleep 45
```

Check the workflow results in the worker terminal and query the shop to see the customer and welcome notification.

```bash
curl -fsS http://127.0.0.1:8787/state
```

| Example | Expected result |
|---|---|
| `event-driven-order-processing` for `O-162` | Publishes the payment status and returns `{"orderId":"O-162","status":"approved"}`. |
| `event-driven-order-processing` for `O-163` | Looks for a charge after the timeout and returns `{"orderId":"O-163","status":"manual-review"}`. |
| `coordinate-onboarding` | Publishes the completion and returns `{"customerId":"C-901","registrationId":"REG-160"}`. |
| `tutorial-publisher` | Each publisher instance completes after sending its event. |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
