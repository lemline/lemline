---
title: Introduction to the Open Workflow DSL
---

# Introduction to Open Workflow DSL

The Open Workflow DSL (Domain Specific Language) provides a powerful and flexible way to define workflows in a
declarative manner. This documentation will guide you through the key concepts and components of the DSL.

## Documentation Structure

This documentation is organized into several main sections:

1. **Workflow Definition**: Learn how to define and structure your workflows
    - [Workflow Definition](dsl-workflow-definition.md)
    - [Workflow Examples](dsl-workflow-examples.md)

2. **Tasks**: Explore the different types of tasks available
    - [Flow Control Tasks](dsl-flow-overview.md)
    - [Data Tasks](dsl-data-flow.md)
        - [Set Task](dsl-task-set.md) - For data manipulation and transformation
        - [Data Flow Management](dsl-data-flow.md) - For input/output handling and validation
    - [Error Handling Tasks](dsl-errors-overview.md)
    - [Time Tasks](dsl-task-wait.md)
    - [Event Tasks](dsl-events-overview.md)
    - [Call Tasks](dsl-call-overview.md)
    - [Run Tasks](dsl-run-overview.md)

3. **Events**: Understand how to work with events
    - [Event Tasks](dsl-events-overview.md)
    - [Event Correlation](dsl-event-correlation.md)

4. **Functions**: Learn about function definitions and usage
    - [Function Definition](dsl-call-function.md)
    - [Function Types](dsl-call-overview.md)

5. **Advanced Features**: Explore advanced workflow capabilities
    - [Retries](dsl-task-try.md)
    - [Secrets](dsl-secrets.md)

## What is the Open Workflow DSL?

The Open Workflow DSL allows you to define workflows using a structured, human-readable format (typically YAML).
Instead of writing imperative code to manage state, handle retries, coordinate tasks, and react to events, you *declare*
the desired flow and behaviour. A compliant workflow engine then interprets this definition and executes the process
reliably.

## Key Principles and Benefits

* **Declarative:** Define *what* your workflow should do, not the low-level *how*. This leads to clearer, more
  maintainable definitions.
* **Vendor-Neutral:** Based on a CNCF (Cloud Native Computing Foundation) specification, promoting portability across
  different runtime platforms and cloud providers.
* **Human-Readable:** Primarily uses YAML, making workflow definitions easy to read, understand, and version control.
* **Comprehensive:** Supports a wide range of constructs, including sequential and parallel execution, conditional
  logic, error handling, timeouts, retries, event handling, and integration with various external systems (functions,
  APIs, message brokers).
* **Extensible:** Designed to integrate custom logic and external services seamlessly through well-defined task types.

## Who is this Documentation For?

This documentation is intended for:

* **Developers:** Building applications that require orchestration or automation of processes.
* **Architects:** Designing distributed systems and event-driven architectures.
* **Operations Engineers:** Automating infrastructure tasks and operational procedures.
* Anyone interested in learning and applying the Open Workflow Specification.

For a more detailed breakdown by role, see [Who is Lemline For?](lemline-who-for.md)

## Getting Started

To get started with the Open Workflow DSL:

1. Read this **Introduction** page thoroughly.
2. Explore the **Core Concepts** section, paying particular attention to [Data Flow](dsl-data-flow.md)
   and [Runtime Expressions](dsl-runtime-expressions.md), as they are used extensively.
3. Review the **Tasks** and **Events** sections to understand the available building blocks.
4. Dive into the specific **Task** or **Concept** pages relevant to your immediate needs. The examples within each page
   are designed to illustrate practical usage.

## Additional Resources

- [Open Workflow Specification](https://open-workflow-specification.org/)
- [Open Workflow Examples](https://github.com/open-workflow-specification/specification/tree/main/examples)
- [Open Workflow Tools](https://github.com/open-workflow-specification)

We hope this documentation helps you effectively leverage the power and flexibility of the Open Workflow DSL.
Happy orchestrating!
