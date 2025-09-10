---
title: What Is Lemline?
---

# What Is Lemline?

Lemline is a modern runtime for the Serverless Workflow DSL (Domain Specific Language) that provides an efficient,
reliable way to orchestrate tasks and services in distributed environments.

## Core Features

* **Serverless Workflow DSL Implementation**: Implements a growing subset of the Serverless Workflow DSL 1.0 standard, a
  CNCF project for workflow definitions (see [Supported Features](lemline-ref-supported-features.md) for details)
* **Event-Driven Architecture**: Built from the ground up with event-driven principles
* **Database-Optional Design**: Can operate without a central database for many workflow types
* **Distributed by Nature**: Designed for cloud-native, distributed environments
* **Highly Resilient**: Built-in error handling, retries, and compensation strategies
* **Protocol Agnostic**: Integrates with HTTP, and soon with gRPC, OpenAPI, AsyncAPI
* **Message Broker Support**: Works with Kafka, RabbitMQ, and soon with other messaging systems
* **Scalable**: Horizontally scale workflow execution across multiple nodes
* **Lightweight**: Minimal footprint with fast startup time

## Key Capabilities

Lemline allows you to:

* Define workflows in a declarative, portable format
* Connect and orchestrate services across protocols and providers
* Handle errors with sophisticated retry mechanisms
* Control flow with conditionals, loops, and parallel execution
* Process events from various sources
* Scale horizontally as efficiently as the choreography pattern

## When to Use Lemline

Lemline is ideal for:

* **Service Orchestration**: Connecting multiple services in a defined sequence
* **Event Processing Pipelines**: Processing streams of events with business logic
* **Long-Running Processes**: Managing processes that span minutes to days
* **Distributed Applications**: Coordinating components across distributed environments
* **Stateful Serverless Applications**: Adding statefulness to serverless architectures

Lemline shines particularly in scenarios where traditional database-backed orchestration brings unnecessary overhead,
complexity, or scaling challenges.

See [Why Lemline Exists](lemline-why-exists.md) for a deeper dive into the problem space and Lemline's approach.

## Who Is Lemline For?

Lemline is designed for different technical roles in modern software development:

* **[Back-end Developers](lemline-who-for.md#back-end-developers)** implementing business logic and services
* **[Architects](lemline-who-for.md#architects-designing-event-driven-systems)** designing distributed systems and integration patterns
* **[Platform Engineers](lemline-who-for.md#platform-engineers)** building reliable, observable platforms
* **[Integration Specialists](lemline-who-for.md#integration-specialists)** connecting disparate systems and services

For a detailed breakdown of how Lemline helps each role and recommended documentation paths, see [Who Is Lemline For?](lemline-who-for.md)
