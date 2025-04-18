# Rules for this modern Kotlin/Quarkus application

# Summary

This file is designed by an experienced Senior Kotlin Developer and
enforces the following best practices:

- SOLID, DRY, KISS, and YAGNI principles
- OWASP security standards
- Task decomposition into minimal, testable units

Key technologies and tools include:

- Kotlin 2.1.10 with Kotlin Gradle
- Quarkus for the runtime environment
- quarkus-junit5 and Mockk for testing
- Hibernate with Panache for database management
- SmallRye Reactive Messaging for asynchronous messaging
- Kotlin coroutines for efficient asynchronous task execution
- Quarkus best practices for logging and monitoring
- Extensible design to easily add new task types and integrations

# Project

name: Lemline
objectives: A modern runtime for the Serverless Workflow DSL.
modules:

- lemline-core: the implementation of the Serverless Workflow DSL itself.
- lemline-worker: the implementation of the runtime for the Serverless Workflow DSL. Using Quarkus Messaging to read and
  publish events, storing data in databases when needed.
- lemline-docs: the documentation of the Serverless Workflow DSL.

# General

- updateDependencies: true
- securityAudit: Follow OWASP guidelines and integrate static code analysis tools.
- methodology: Break every task into the smallest units; approach each step methodically.

# Kotlin

- version: 2.1.10
- buildTool: Kotlin Gradle
- codeStyle: Enforce Kotlin Coding Conventions & use static analysis for linting.
- dependenciesVersionManagement = Use the latest stable versions of all libraries.

# Testing

- frameworks: Use "quarkus-junit5" and "Mockk"
- coverage: High unit and integration test coverage is required.
- testing approach: Each unit should be isolated and methodically tested.

# Runtime

- framework: Quarkus
- logging: Implement Quarkus best practices for logging and monitoring.
- monitoring: Enable health checks, metrics, and distributed tracing as needed.
- extensibility: Design the runtime to be easily extended with new task types and integrations.

# Database

- orm: Hibernate with Panache
- connectionManagement: Ensure efficient connection pooling and resource management.
- migrations: Use FlyWay migration tools for schema changes.
- performance: Enforce query optimization best practices: use proper indexing, caching strategies, and batch processing.
  Continuously monitor and profile database queries to identify bottlenecks, ensuring efficient execution even at high
  throughput.

# Messaging

- library: SmallRye Reactive Messaging
- syncProcessing: Utilize Kotlin coroutines for asynchronous execution and efficient resource utilization. Implement
  robust error handling and backpressure management in message flows."

# Asynchronous

- strategy: Leverage Kotlin coroutines to manage asynchronous task execution effectively.
- bestPractices:
    - Use structured concurrency to manage coroutine lifecycles and ensure proper resource cleanup.
    - Choose appropriate CoroutineScopes (e.g., application, supervisor) based on task requirements.
    - Handle exceptions within coroutines to prevent resource leaks.
    - Optimize context switching to minimize overhead in high-load scenarios.
    - Integrate with Quarkus' reactive paradigms for seamless non-blocking operations.

# Development Principles

- principles: "SOLID", "DRY", "KISS", "YAGNI", "OWASP"
- taskDecomposition: Break down every feature into small, testable, and maintainable units.
- methodology: Approach development tasks methodically, ensuring clarity and simplicity.

# References folder

- the /references/sdk-java/types/ folder contains the classes of the Serverless Workflow Java reference implementation
- in /references/workflow.yaml you can find the YAML version of the Serverless Workflow schema definition
- the dsl.md and dsl-reference.md files contain the description of the DSL that is implemented in this project
- the /lemline-docs folder contains the documentation of the Serverless Workflow DSL. But it is not yet entirely
  checked. In case of doubt, use the doc-reference.md document.
