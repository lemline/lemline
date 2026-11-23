---
title: Event Tasks
---

<!-- Examples are validated -->


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
  - Start or resume workflows based on external events
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

## Examples

### Order Processing with Event Handling

<code-block lang="yaml" src="workflows/dsl-events-overview/order-processing-with-event-handling.yaml"/>






### Event-Based Microservice Coordination

<code-block lang="yaml" src="workflows/dsl-events-overview/event-based-microservice-coordination.yaml"/>

Event tasks form the foundation of event-driven architecture within workflows, 
enabling responsive, loosely-coupled systems that can react to changes across distributed environments. 
By using Listen and Emit tasks appropriately, workflows can participate in complex event ecosystems, 
coordinating business processes that span multiple services while maintaining resilience and scalability. 


