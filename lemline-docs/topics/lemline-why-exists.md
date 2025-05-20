---
title: Why Lemline Exists
---

# Why Lemline Exists

Lemline was created to address fundamental challenges in workflow orchestration, particularly the hidden costs and limitations of traditional orchestration engines.

## The Hidden Cost of Traditional Orchestration

Traditional workflow engines share a common architecture: they rely on a central database to store workflow state. While this approach works, it comes with significant costs:

### Database Overhead

* **Transactional Bottleneck**: Every workflow step requires database transactions
* **High IOPS Requirements**: Leading to expensive infrastructure needs
* **Scaling Challenges**: Vertical scaling becomes necessary as workloads grow
* **Consistency Trade-offs**: Distributed databases bring CAP theorem complications

### Failure Points

* **Single Point of Failure**: Database availability directly impacts workflow execution
* **Recovery Complexity**: Recovering from database failures requires careful planning
* **Transaction Boundaries**: Long-running transactions create locking and timeout issues
* **State Corruption Risks**: Partial updates can lead to inconsistent workflow states

## The Classic Tradeoff: Choreography vs. Orchestration

When designing distributed systems, architects typically face this fundamental choice:

### Choreography
* **Benefits**: Decentralized, highly scalable, resilient to component failures
* **Drawbacks**: Difficult to track overall process state, complex debugging, no central visibility

### Orchestration
* **Benefits**: Centralized control, clear process visibility, simpler implementation
* **Drawbacks**: Database dependency, potential bottlenecks, scaling challenges

## How Lemline Delivers Orchestration Without a Central Database

Lemline breaks this tradeoff by offering a third path:

### Event-Driven State Management

* **Event Sourcing Principles**: Workflow state is derived from the sequence of events
* **Minimal State Storage**: Only stores what's necessary for correlation and resumability
* **Message Brokers as Backbone**: Leverages existing message infrastructure for reliability

### Smart Persistence Strategy

* **Selective Database Usage**: Uses database only when absolutely necessary
* **Outbox Pattern**: Ensures reliable message delivery with transactional guarantees
* **Wait and Timer Delegation**: Offloads time-based operations to specialized components
* **Optimized for Read/Write Patterns**: Database schema designed for minimal contention

### Distributed Execution Model

* **Node-Based Processing**: Workflow state flows through a graph of processing nodes
* **Horizontal Scaling**: Multiple runner instances process events concurrently
* **Position Tracking**: Precisely tracks execution position without constant db updates

## Where Lemline's Philosophy Meets Engineering

Lemline embodies these key architectural principles:

1. **Event-First Thinking**: Events are first-class citizens, not just side effects
2. **Pay-for-What-You-Use**: Only use database persistence when the workflow requires it
3. **Minimize State**: Store only what's necessary to resume execution
4. **Embrace Messaging**: Use message brokers for what they're good at - reliable message delivery
5. **Single Responsibility**: Each component does one thing well

The result is a workflow engine that delivers:

* The visibility and control of orchestration
* The scalability and resilience of choreography
* Significantly reduced database overhead
* Lower operational costs
* Greater resilience to failures

Lemline aims to make sophisticated workflow orchestration accessible without demanding enterprise-grade database infrastructure, bringing the best of both worlds to your distributed systems.