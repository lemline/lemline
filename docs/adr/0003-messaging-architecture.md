# [ADR-0003] Messaging Architecture

## Status

Accepted

## Context

The Lemline project implements a runtime for the Serverless Workflow DSL, which requires a robust messaging architecture to handle communication between workflow components, external systems, and to support event-driven workflow execution. We needed to decide on a messaging architecture that would be reliable, scalable, and aligned with the Serverless Workflow specification.

## Decision

We have decided to implement a messaging architecture based on SmallRye Reactive Messaging with the following characteristics:

1. **Event-driven Communication**: The system uses an event-driven approach where components communicate through events and messages.

2. **Reactive Messaging**: We use SmallRye Reactive Messaging, which provides a reactive programming model for handling messages.

3. **Outbox Pattern**: We implement the Outbox pattern to ensure reliable message delivery, even in the face of failures.

4. **Message Types**: We define specific message types for different kinds of communication, such as workflow events, task events, and system events.

5. **Asynchronous Processing**: Messages are processed asynchronously using Kotlin coroutines, allowing for efficient resource utilization.

6. **Error Handling**: We implement robust error handling for message processing, including retry mechanisms and dead-letter queues.

7. **Backpressure Management**: We implement backpressure management to handle high message volumes without overwhelming the system.

## Consequences

### Positive

- **Reliability**: The Outbox pattern ensures that messages are not lost, even if the system fails during processing.
- **Scalability**: The asynchronous and reactive approach allows the system to scale to handle high message volumes.
- **Flexibility**: The event-driven architecture makes it easier to add new components and integrate with external systems.
- **Resource Efficiency**: Asynchronous processing with coroutines allows for efficient use of system resources.
- **Resilience**: Robust error handling and retry mechanisms make the system resilient to failures.

### Negative

- **Complexity**: Event-driven architectures introduce complexity, especially for debugging and understanding the flow of execution.
- **Eventual Consistency**: The asynchronous nature of the system means that it operates under eventual consistency, which can be challenging to reason about.
- **Operational Overhead**: Managing message brokers and ensuring reliable message delivery adds operational overhead.

## Alternatives Considered

### Synchronous Communication

A synchronous communication approach would use direct method calls or synchronous API calls between components. This approach was rejected because:
- It would make the system less resilient to failures, as a failure in one component would directly impact others.
- It would limit scalability, as components would be tightly coupled.
- It would make it harder to implement features like asynchronous workflow execution and event-driven transitions.

### Custom Messaging Implementation

Implementing a custom messaging solution instead of using SmallRye Reactive Messaging was considered. This approach was rejected because:
- It would require significant development effort to implement features that are already provided by SmallRye Reactive Messaging.
- It would likely result in a less robust and well-tested solution.
- It would not leverage the integration with Quarkus and other components that SmallRye Reactive Messaging provides.

## References

- [SmallRye Reactive Messaging](https://smallrye.io/smallrye-reactive-messaging/)
- [Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Reactive Streams Specification](https://www.reactive-streams.org/)