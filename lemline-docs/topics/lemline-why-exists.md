---
title: Why Lemline Exists
---

# Why Lemline Exists

Lemline was created to address fundamental challenges in workflow orchestration, particularly the hidden costs and
limitations of traditional orchestration engines.

## The Hidden Cost of Traditional Orchestration

Traditional workflow engines share a common architecture: they rely on a central database to store workflow state. While
this approach works, it comes with significant costs that often become apparent only as systems scale.

### Database Overhead

The database dependency in traditional workflow engines creates a transactional bottleneck where every workflow step
requires database transactions. This leads to high IOPS (Input/Output Operations Per Second) requirements, resulting in
increasingly expensive infrastructure needs as your system grows.

For example, a workflow with 10 steps might require 20-30 database operations to complete. At scale, with thousands of
concurrent workflows, this can mean hundreds of thousands of database operations per minute. Even well-provisioned
databases can become overwhelmed under these conditions.

As workloads grow, vertical scaling becomes necessary since the database often becomes the system's primary bottleneck.
Organizations frequently find themselves upgrading to increasingly expensive database tiers just to maintain acceptable
performance, with costs growing faster than actual business value.

When organizations attempt to solve this with distributed databases, they immediately face the CAP theorem's trade-offs,
often sacrificing consistency or availability in ways that complicate workflow guarantees.

### Failure Points

The database dependency also creates a single point of failure where database availability directly impacts workflow
execution. Even brief database outages can halt all workflow processing, potentially affecting critical business
operations.

Recovery from database failures requires careful planning and often complex procedures. In many traditional
orchestration systems, recovering workflow state after a significant database outage can be challenging and error-prone.

Long-running transactions create locking and timeout issues, especially for workflows that span minutes or hours. This
often forces architects to implement complex compensation mechanisms or custom recovery procedures.

There's also an ever-present risk of state corruption. Partial updates during system failures can lead to inconsistent
workflow states that may require manual intervention to resolve. One organization we worked with spent weeks recovering
workflow state after a database corruption incident, highlighting how fragile these systems can become.

## The Classic Tradeoff: Choreography vs. Orchestration

When designing distributed systems, architects typically face a fundamental choice between two approaches: choreography
and orchestration.

### Choreography

In a choreography-based system, each component acts autonomously based on events it receives, without a central
coordinator. Think of it as a dance where each dancer moves independently according to music and the movements of other
dancers, but without a choreographer directing the whole performance.

This approach is decentralized and highly scalable. Since components operate independently, the system can easily scale
horizontally. It's also resilient to component failures, as the failure of one component doesn't necessarily halt the
entire process.

However, choreography makes it difficult to track overall process state. If you want to know whether a business process
is complete or where it stands, you need to query multiple systems and piece together the information. Debugging becomes
complex because there's no single place to observe the flow. There's also no central visibility, making it harder to
provide status updates to users or stakeholders.

Consider an e-commerce order process implemented with choreography. The order service emits an "OrderCreated" event,
which the inventory service consumes to reserve stock, then emits "StockReserved", which payment service consumes to
process payment, and so on. If a customer calls asking about their order status, determining exactly where their order
is in this process can require checking multiple systems.

### Orchestration

In contrast, orchestration uses a central coordinator to direct the process flow. Like a conductor leading an orchestra,
the orchestrator tells each component when to act and coordinates the overall process.

Orchestration provides centralized control and clear process visibility. There's a single place to check process status,
making monitoring and debugging simpler. Implementation is often more straightforward because process logic is
centralized rather than distributed across services.

However, traditional orchestration relies heavily on database persistence, creating potential bottlenecks as discussed
earlier. This approach often faces scaling challenges when transaction volumes increase, requiring expensive
infrastructure upgrades.

An orchestrated e-commerce order process would have a central workflow engine that calls the inventory service to
reserve stock, waits for a response, then calls the payment service, and so on. While this makes order tracking simple,
the orchestrator can become a bottleneck if thousands of orders are being processed simultaneously.

## How Lemline Delivers Orchestration Without a Central Database

Lemline breaks this tradeoff by offering a third path that combines the best aspects of both approaches.

### Event-Driven State Management

Lemline fundamentally differs from traditional workflow engines in how it manages state. Instead of storing workflow
state in a central database, Lemline transports the workflow state within the messages themselves. Each message carries
precisely the information needed for the next execution steps.

When a workflow node completes its processing, it doesn't write its entire state back to a database. Instead, it
packages just the essential state required for subsequent steps into a message and sends it forward. The next node
receives this message containing exactly what it needs to continue the workflow. This creates a continuous flow of
execution where state moves alongside the execution path rather than being centrally stored and retrieved.

For example, in an order processing workflow, when the payment verification step completes, it doesn't update a
central "order" document. Instead, it sends a message containing the verified payment details and order ID directly to
the inventory reservation step. The inventory step receives exactly the information it needs, without having to query a
database for the complete order context.

This approach dramatically reduces database operations since state transitions don't require database updates. It also
improves resilience, as workflow execution can continue even if the database is temporarily unavailable. The workflow
state is effectively "in motion" rather than statically stored.

By leveraging existing message infrastructure such as Kafka or RabbitMQ as its backbone, Lemline utilizes systems
already designed for high throughput and reliable message delivery. This message-carried state approach enables
workflows to progress efficiently with minimal external state dependency.

### Smart Persistence Strategy

Lemline uses a database only when absolutely necessary. For workflows that don't require long-term state persistence,
Lemline can operate entirely without database interactions, using only message brokers for communication and state
management.

Only time-based operations like waits and timeouts are offloaded to a database.

The system implements the outbox pattern to ensure reliable message delivery with transactional guarantees.
When a database is used, operations are batched and optimized to minimize transactions and contention.

The database schema is designed specifically for minimal contention, with careful attention to read/write patterns that
occur in workflow processing. This prevents the database from becoming a bottleneck even when it is used.

### Distributed Execution Model

Lemline uses a node-based processing model where workflow state flows through a graph of processing nodes. This allows
for fine-grained tracking of execution position and enables partial progress even during system disruptions.

Multiple runner instances can process events concurrently, enabling true horizontal scaling. As load increases, simply
add more runner instances to handle the additional throughput without changing the database tier.

The system tracks execution position precisely without requiring constant database updates. This position tracking
allows workflows to be paused and resumed efficiently, even across different runner instances.

For example, in a complex order fulfillment workflow, Lemline might represent each step (order validation, payment
processing, inventory check, etc.) as nodes in a graph. As the workflow executes, tokens move through this graph, with
position tracked efficiently without constant database writes.

## Where Lemline's Philosophy Meets Engineering

Lemline embodies several key architectural principles that shape its design and implementation.

Events are treated as first-class citizens, not just side effects. The entire system is designed around event flow and
processing, making event handling efficient and native to the architecture. This contrasts with systems where events are
secondary to the primary state-based processing model.

The system follows a "pay-for-what-you-use" philosophy, using database persistence only when the workflow explicitly
requires it. Simple, short-lived workflows can execute entirely in memory with message broker support, while complex,
long-running workflows can utilize database persistence as needed.

Lemline minimizes state storage by keeping only what's necessary to resume execution. This reduces storage requirements
and improves performance by minimizing data transferred between components.

The architecture embraces messaging infrastructure for what it excels at: reliable message delivery. By building on top
of battle-tested message brokers, Lemline inherits their reliability and scalability characteristics.

The result is a workflow engine that delivers both the visibility and control traditionally associated with
orchestration alongside the scalability and resilience typically found in choreography. By significantly reducing
database overhead, Lemline offers lower operational costs and greater resilience to failures.
