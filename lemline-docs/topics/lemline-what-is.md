---
title: What Is Lemline?
---

# What Is Lemline?

Lemline is a modern runtime for the Serverless Workflow DSL (Domain Specific Language) that provides an efficient, reliable way to orchestrate tasks and services in distributed environments.

## Core Features

* **Serverless Workflow DSL Implementation**: Fully supports the Serverless Workflow DSL 1.0 standard, a CNCF project for workflow definitions
* **Event-Driven Architecture**: Built from the ground up with event-driven principles
* **Database-Optional Design**: Can operate without a central database for many workflow types
* **Distributed by Nature**: Designed for cloud-native, distributed environments
* **Highly Resilient**: Built-in error handling, retries, and compensation strategies
* **Protocol Agnostic**: Integrates with HTTP, gRPC, OpenAPI, AsyncAPI, and custom function calls
* **Message Broker Support**: Works with Kafka, RabbitMQ, and other messaging systems
* **Scalable**: Horizontally scale workflow execution across multiple nodes
* **Lightweight**: Minimal footprint with fast startup time

## Key Capabilities

Lemline allows you to:

* Define workflows in a declarative, portable format
* Connect and orchestrate services across protocols and providers
* Handle errors with sophisticated retry mechanisms
* Control flow with conditionals, loops, and parallel execution
* Process events from various sources
* Maintain state without a central database (for supported workflow types)
* Scale horizontally to handle varying loads

## When to Use Lemline

Lemline is ideal for:

* **Service Orchestration**: Connecting multiple services in a defined sequence
* **Event Processing Pipelines**: Processing streams of events with business logic
* **Long-Running Processes**: Managing processes that span minutes to days
* **Distributed Applications**: Coordinating components across distributed environments
* **Stateful Serverless Applications**: Adding statefulness to serverless architectures

Lemline shines particularly in scenarios where traditional database-backed orchestration brings unnecessary overhead, complexity, or scaling challenges.

See [Why Lemline Exists](lemline-why-exists.md) for a deeper dive into the problem space and Lemline's approach.