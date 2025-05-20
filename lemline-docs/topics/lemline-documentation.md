---
title: Lemline Documentation
---

# Lemline Documentation

Welcome to the official documentation for Lemline, the modern runtime for Serverless Workflow DSL.

This documentation is structured using the Diátaxis framework, organizing content into four distinct types to better
serve your needs:

## 🏠 Home

* [What Is Lemline?](lemline-what-is.md)
* [Why Lemline Exists](lemline-why-exists.md)
* [Documentation Guide](lemline-doc-guide.md)
* [Who Is Lemline For?](lemline-who-for.md)
* [Getting Started Fast](lemline-getting-started.md)

## 🎓 Tutorials

*Learning-oriented experiences for skill acquisition*

* [Tutorial: Hello, Workflow!](lemline-tutorial-hello.md)
* [Tutorial: Database-Less Order Processing](lemline-tutorial-order-processing.md)
* [Tutorial: Waits, Fan-In, and Timers](lemline-tutorial-waits.md)
* [Tutorial: Streaming Sensor Events (IoT)](lemline-tutorial-iot.md)

## 🛠️ How-to Guides

*Task-oriented directions for applying skills*

### 🔧 Core Operations

* [How to define a workflow](lemline-howto-define-workflow.md)
* [How to publish and run a workflow](lemline-howto-publish-run.md)
* [How to monitor workflow execution](lemline-howto-monitor.md)
* [How to cancel, resume, or suspend a workflow](lemline-howto-lifecycle.md)

### 🎯 Flow Control

* [How to define conditional branches (switch)](lemline-howto-conditional.md)
* [How to execute tasks in parallel (fork)](lemline-howto-parallel.md)
* [How to run loops (for, while)](lemline-howto-loops.md)
* [How to jump between tasks (then, exit, end)](lemline-howto-jumps.md)

### 🧩 Working with Tasks

* [How to make an HTTP call](lemline-howto-http.md)
* [How to use OpenAPI-defined services](lemline-howto-openapi.md)
* [How to run scripts or containers](lemline-howto-run.md)
* [How to call gRPC functions](lemline-howto-grpc.md)
* [How to emit and listen for events](lemline-howto-events.md)

### 🧮 Data Management

* [How to pass data between tasks](lemline-howto-data-passing.md)
* [How to use input, output, and export](lemline-howto-io.md)
* [How to write jq runtime expressions](lemline-howto-jq.md)
* [How to validate inputs and outputs with schemas](lemline-howto-schemas.md)

### ⚙️ Resilience and Error Handling

* [How to use try/catch to handle faults](lemline-howto-try-catch.md)
* [How to retry failed tasks with backoff](lemline-howto-retry.md)
* [How to raise custom errors](lemline-howto-custom-errors.md)
* [How to debug faults in task execution](lemline-howto-debug.md)

### 🔐 Authentication and Secrets

* [How to configure OAuth2 authentication](lemline-howto-oauth2.md)
* [How to access secrets securely](lemline-howto-secrets.md)
* [How to work with API keys](lemline-howto-api-keys.md)
* [How to configure TLS](lemline-howto-tls.md)

### 🚀 Runner Configuration

* [How to configure lemline.yaml](lemline-howto-config.md)
* [How to connect to brokers (Kafka, Pulsar, RabbitMQ)](lemline-howto-brokers.md)
* [How to configure observability (logs, metrics)](lemline-howto-observability.md)
* [How to scale the runner (standalone, clustered)](lemline-howto-scaling.md)

## 📖 Reference

*Information-oriented, neutral descriptions*

### 🧾 DSL Reference

* [Complete Serverless Workflow DSL Syntax](lemline-ref-dsl-syntax.md)
* [Task Types Reference](lemline-ref-task-types.md)

### ⚙️ Runner Reference

* [Configuration Reference (lemline.yaml)](lemline-ref-config.md)
* [Environment Variables](lemline-ref-env-vars.md)
* [Metrics and Requirements](lemline-ref-metrics.md)

### 🖥️ CLI Reference

* [Command Syntax and Options](lemline-ref-cli.md)
* [Exit Codes and Error States](lemline-ref-errors.md)

### 🔐 Auth & Secrets Reference

* [Authentication Reference](lemline-ref-auth.md)
* [Secrets Management Reference](lemline-ref-secrets.md)

### 🌐 Protocol Support Reference

* [HTTP Support](lemline-ref-http.md)
* [gRPC Support](lemline-ref-grpc.md)
* [OpenAPI Support](lemline-ref-openapi.md)
* [AsyncAPI Support](lemline-ref-asyncapi.md)
* [Connection Configuration](lemline-ref-connections.md)
* [Serialization Formats](lemline-ref-serialization.md)

## 🧠 Explanations

*Understanding-oriented discussions*

* [Why Event-Driven Orchestration Beats the Database](lemline-explain-event-driven.md)
* [How Lemline Executes Workflows](lemline-explain-execution.md)
* [What is Serverless Workflow DSL?](lemline-explain-sw-dsl.md)
* [Inside a Fan-In](lemline-explain-fan-in.md)
* [How Lemline Handles Time](lemline-explain-time.md)
* [Error Propagation and Resilience Model](lemline-explain-errors.md)
* [Runtime Expressions and jq](lemline-explain-jq.md)
* [Security Model](lemline-explain-security.md)
* [Scaling Lemline Runners](lemline-explain-scaling.md)

## 📁 Examples Library

*Complete, practical examples with context*

* [Order Processing Workflow](lemline-examples-order.md)
* [IoT Event Processing](lemline-examples-iot.md)
* [Multi-step API Orchestration](lemline-examples-api.md)
* [Long-running Process with Timeouts](lemline-examples-timeouts.md)
* [Error Handling with Compensation](lemline-example-compensation.md)

## 📈 Observability & Performance

*Practical guidance combined with conceptual backing*

* [Understanding Lifecycle Events](lemline-observability-lifecycle.md)
* [Performance Comparison vs Traditional Engines](lemline-observability-performance.md)
* [Database I/O Analysis](lemline-observability-io.md)
* [Infrastructure Sizing Guidelines](lemline-observability-sizing.md)

## 📣 Community and Contribution

*Enable community growth*

* [Contribution Guide](lemline-community-contribution.md)
* [Documentation Style Guide](lemline-community-style.md)
* [Diátaxis for Contributors](lemline-community-diataxis.md)
