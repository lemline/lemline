---
title: Introduction to the Serverless Workflow DSL
---

# Introduction to the Serverless Workflow DSL

Welcome! This documentation provides a comprehensive guide to the **Serverless Workflow Domain Specific Language (DSL)**, a powerful, vendor-neutral standard for defining stateful, orchestrated workflows. Whether you're automating simple business logic sequences or building complex, event-driven, distributed systems, this DSL offers a declarative approach to defining your process logic.

## What is the Serverless Workflow DSL?

The Serverless Workflow DSL allows you to define workflows using a structured, human-readable format (typically YAML). Instead of writing imperative code to manage state, handle retries, coordinate tasks, and react to events, you *declare* the desired flow and behaviour. A compliant workflow engine then interprets this definition and executes the process reliably.

## Key Principles and Benefits

*   **Declarative:** Define *what* your workflow should do, not the low-level *how*. This leads to clearer, more maintainable definitions.
*   **Vendor-Neutral:** Based on a CNCF (Cloud Native Computing Foundation) specification, promoting portability across different runtime platforms and cloud providers.
*   **Human-Readable:** Primarily uses YAML, making workflow definitions easy to read, understand, and version control.
*   **Comprehensive:** Supports a wide range of constructs, including sequential and parallel execution, conditional logic, error handling, timeouts, retries, event handling, and integration with various external systems (functions, APIs, message brokers).
*   **Extensible:** Designed to integrate custom logic and external services seamlessly through well-defined task types.

## Who is this Documentation For?

This documentation is intended for:

*   **Developers:** Building applications that require orchestration or automation of processes.
*   **Architects:** Designing distributed systems and event-driven architectures.
*   **Operations Engineers:** Automating infrastructure tasks and operational procedures.
*   Anyone interested in learning and applying the Serverless Workflow standard.

## How This Documentation is Structured

This site is organized to help you quickly find the information you need:

*   **Core Concepts:** Fundamental ideas underpinning the DSL, such as [Data Flow](dsl-data-flow.md), [Runtime Expressions](dsl-runtime-expressions.md), [Error Handling](dsl-error-handling.md), [Authentication](dsl-authentication.md), and more. Understanding these is crucial for effective workflow design.
*   **[Tasks Overview](dsl-tasks-overview.md):** Introduces the building blocks of workflows. Specific task types are detailed within this section, including control flow tasks (`Do`, `For`, `Switch`, `Try`, `Raise`), data manipulation (`Set`), timing (`Wait`), parallelism (`Fork`), and external interactions grouped under:
    *   **[Call Tasks](dsl-call-overview.md):** For invoking functions, HTTP/gRPC APIs, OpenAPI/AsyncAPI definitions.
    *   **[Run Tasks](dsl-run-overview.md):** For executing containers, scripts, shells, or other workflows.
*   **[Events Overview](dsl-event-overview.md):** Explains how workflows can interact with events, covering how to [Emit Events](dsl-task-emit.md), [Listen for Events](dsl-task-listen.md), and perform advanced [Event Correlation](dsl-event-correlation.md).
*   **Other Topics:** Covers essential aspects like [Timeouts](dsl-timeouts.md), [Retries](dsl-retries.md), [Secrets Management](dsl-secrets.md), etc.

## Getting Started

1.  Read this **Introduction** page thoroughly.
2.  Explore the **Core Concepts** section, paying particular attention to [Data Flow](dsl-data-flow.md) and [Runtime Expressions](dsl-runtime-expressions.md), as they are used extensively.
3.  Review the **[Tasks Overview](dsl-tasks-overview.md)** and **[Events Overview](dsl-event-overview.md)** pages to understand the available building blocks.
4.  Dive into the specific **Task** or **Concept** pages relevant to your immediate needs. The examples within each page are designed to illustrate practical usage.

We hope this documentation helps you effectively leverage the power and flexibility of the Serverless Workflow DSL. Happy orchestrating! 