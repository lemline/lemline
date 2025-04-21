# Lemline

[![License](https://img.shields.io/badge/License-BSL%201.1-blue.svg)](LICENSE.md)

> **⚠️ Warning: Active Development**
> Lemline is currently under active development and should be considered alpha software.
> It is **not yet recommended for production use**. APIs and functionality may change without notice.

Lemline is a modern runtime for the [Serverless Workflow DSL](https://github.com/serverlessworkflow/specification),
enabling the execution of complex workflows defined in YAML or JSON on top of your existing infrastructure. It leverages
modern best practices for performance, reliability, and extensibility.

## 🎯 Objectives

Lemline aims to be a highly efficient orchestration engine. It implements an event-driven orchestration pattern able to
operate on a messaging system but without relying on a database most of the time.

The state of a workflow instance is compressed and transported within events. The database is only used on limited cases
when stricly needed, e.g. for managing:

- delays
- retries
- fan-in

## ✨ Features

* **Serverless Workflow DSL Execution:** Faithfully implements
  the [Serverless Workflow specification](https://serverlessworkflow.io) for defining and running workflows.
* **Modern Tech Stack:** Utilizes Quarkus, Hibernate Panache, and SmallRye Reactive Messaging for a robust and efficient
  runtime.
* **Asynchronous Processing:** Leverages Kotlin Coroutines and reactive principles for high-throughput, non-blocking
  task execution.
* **Database Integration:** Supports persistent workflow state and data using Hibernate Panache.
    * *Note: While Quarkus + Hibernate Panache allows compatibility with numerous relational databases, Lemline is
      currently tested with PostgreSQL and MySQL.*
* **Event-Driven:** Built on Quarkus's reactive messaging capabilities (SmallRye Reactive Messaging) for seamless
  integration with event streams.
    * *Note: While SmallRye Reactive Messaging supports various brokers (Kafka, AMQP, MQTT, etc.), Lemline is currently
      tested with Kafka and RabbitMQ.*

## 📦 Modules

* **`lemline-core`:** Contains the core implementation of the Serverless Workflow DSL types and logic.
* **`lemline-worker`:** Provides the Quarkus-based runtime environment. It uses reactive messaging to read and publish
  events, manages workflow state, and interacts with databases.
* **`lemline-docs`:** (Planned/Included) Documentation for the project and potentially the DSL nuances specific to
  Lemline.

## 🚀 Getting Started

### Prerequisites

* Java Development Kit (JDK) 17+
* A running instance of a supported database (e.g., PostgreSQL, MySQL)
* A message broker like Kafka or RabbitMQ

### Building

```bash
./gradlew build
```

### Running the Worker (Development Mode)

Ensure your database and message broker (if needed) are configured in
`lemline-worker/src/main/resources/application.properties`.

```bash
./gradlew :lemline-worker:quarkusDev
```

The worker will start, connect to the database/broker, and begin processing workflows.

You can find more information about database and message broker configuration in
the [lemline-worker README](lemline-worker/README.md).

### Running Tests

```bash
./gradlew test
```

## 📚 Documentation

* **Serverless Workflow DSL Specification:
  ** [Official Specification](https://github.com/serverlessworkflow/specification)
* **Lemline DSL Reference:** See the `references/dsl.md` and `references/dsl-reference.md` files for the specific
  version and details implemented in this project.
* **Worker Configuration:** See `lemline-worker/README.md` (if it exists or needs creation) for detailed configuration
  options.

## 🤝 Contributing

Contributions are welcome! Please follow standard practices:

1. Fork the repository.
2. Create a new branch (`git checkout -b feature/your-feature-name`).
3. Make your changes.
4. Write tests for your changes.
5. Ensure all tests pass (`./gradlew test`).
6. Format your code according to Kotlin conventions.
7. Commit your changes (`git commit -m 'Add some feature'`).
8. Push to the branch (`git push origin feature/your-feature-name`).
9. Open a Pull Request.

## 📜 License

This project is licensed under the [Business Source License 1.1](LICENSE.md).

## Current development

Currently supporting:

### Tasks:

- [x] Switch
- [x] Set
- [x] Do
- [x] Raise
- [ ] Listen
- [ ] Emit
- [ ] Fork
- [x] For
- Try:
    - [x] Retry
    - [x] Catch
- [x] Raise
- [x] Wait
- Call:
    - [x] HTTP
    - [ ] OpenAPI
    - [ ] gRPC
    - [ ] AsyncAPI
- Run:
    - [ ] Container
    - [ ] Script
    - [ ] Shell
    - [ ] Workflow

### Flow:

- [x] Status

- [x] Error Management

- Directives:
    - [x] continue
    - [x] exit
    - [x] end
    - [x] goto (named task)

- Schema Validation:
    - [x] Input
    - [x] Output

- Expressions:
    - [x] Input
    - [x] Output
    - [x] Export
    - [x] Scope: runtime, workflow, task...

- Timeouts:
    - [ ] Workflow
    - [ ] Task

- [ ] Schedule

### Lifecycle events:

- [ ] Workflow
- [ ] Tasks

### Others

#### Authentication:

- [x] Basic
- [x] Bearer
- [ ] Certificate
- [x] Digest
- [x] OAUTH2
- [x] OpenIdConnect

#### [ ] Catalog:

#### [ ] Extension


