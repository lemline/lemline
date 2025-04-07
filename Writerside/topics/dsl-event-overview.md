---
title: Events Overview
---

# Events Overview

## Introduction

Events are a fundamental concept in the Serverless Workflow DSL, enabling workflows to react to occurrences, communicate asynchronously, and interact with external systems in a decoupled manner. They represent notifications that something significant has happened, either within the workflow system itself (like lifecycle events) or in the broader environment the workflow operates in.

Using events allows you to build more resilient, scalable, and responsive applications by moving away from tightly coupled request/response interactions towards choreography and reaction based on observed state changes or triggers.

## Core Concepts

The DSL provides specific tasks and mechanisms for handling events:

*   **[Emit Task](dsl-task-emit.md):** Used by a workflow to **produce** or **publish** an event. This allows a workflow to signal its own state changes, report results, or trigger other processes. Events emitted typically conform to the [CloudEvents specification](https://cloudevents.io/) for interoperability.
*   **[Listen Task](dsl-task-listen.md):** Used by a workflow to **pause execution** and **wait** for one or more specific events to occur before proceeding. This is the primary mechanism for consuming events and reacting to external stimuli or messages from other services.
*   **[Event Correlation](dsl-event-correlation.md):** An advanced mechanism used within `Event Filters` (primarily in the `Listen` task) to match incoming events based on **dynamic data** extracted from the event payload and compared against the workflow's current context. This allows workflows to wait for events that are specifically relevant to the instance's ongoing process (e.g., matching an `orderId`).

## Common Use Cases

Leveraging events in your workflows unlocks several powerful patterns:

*   **Waiting for External Triggers:** A workflow can start or resume based on an external event, such as a new file arriving in storage, a message landing on a queue, or a webhook notification. (Often implemented using `Listen` or platform-specific triggers).
*   **Reacting to System Changes:** Workflows can listen for events indicating changes in other systems (e.g., inventory updates, user profile changes) and trigger appropriate actions.
*   **Asynchronous Task Completion:** A workflow can initiate a long-running operation via a call task and then use `Listen` to wait for a completion event from that operation, rather than blocking synchronously.
*   **Inter-Workflow Communication:** One workflow can `Emit` an event that triggers or provides data to another workflow instance via `Listen`.
*   **Saga Pattern / Compensating Transactions:** Events can signal the success or failure of steps in a distributed transaction, allowing other services or workflows to react and perform compensating actions if necessary.
*   **Decoupled Integration:** Services can communicate via events without needing direct knowledge of each other, promoting loose coupling and independent evolution.

Understanding and utilizing these event-handling capabilities is key to building robust and sophisticated serverless workflows. 