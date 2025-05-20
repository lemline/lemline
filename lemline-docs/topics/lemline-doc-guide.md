---
title: Documentation Guide
---

# Documentation Guide

The Lemline documentation is structured following the [Diátaxis framework](https://diataxis.fr/), which organizes
technical documentation into four distinct types based on how users approach and use documentation. This guide will help
you navigate to the right type of documentation for your needs.

## The Four Documentation Types

### 🎓 Tutorials

**Purpose**: Learning-oriented content focused on practical experience.

**When to use**:

- You're new to Lemline and want to learn by doing
- You want a guided, step-by-step journey to build confidence
- You prefer practical experience over conceptual understanding

[Browse Tutorials →](lemline-tutorial-hello.md)

### 🛠️ How-to Guides

**Purpose**: Task-oriented instructions focused on solving specific problems.

**When to use**:

- You know what you want to accomplish and need steps to do it
- You're looking for practical solutions to specific challenges
- You want focused guidance without detailed explanations

[Browse How-to Guides →](lemline-howto-define-workflow.md)

### 📖 Reference

**Purpose**: Information-oriented content with precise, comprehensive details.

**When to use**:

- You need exact specifications or syntax details
- You want to look up specific properties, parameters, or options
- You're implementing something that requires precise details

[Browse Reference →](lemline-ref-dsl-syntax.md)

### 🧠 Explanations

**Purpose**: Understanding-oriented discussions of concepts and approaches.

**When to use**:

- You want to understand the "why" behind design decisions
- You're interested in concepts, not just practical steps
- You need deeper architectural insights to make design decisions

[Browse Explanations →](lemline-explain-event-driven.md)

## Finding What You Need

### By Role

* **As a Developer** new to Lemline:
    1. Start with [What is Lemline?](lemline-what-is.md) and [Getting Started Fast](lemline-getting-started.md)
    2. Follow the [Hello Workflow Tutorial](lemline-tutorial-hello.md)
    3. Explore [How-to Guides](lemline-howto-define-workflow.md) for specific tasks

* **As an Architect**:
    1. Read [Why Lemline Exists](lemline-why-exists.md) to understand the philosophy
    2. Dive into the [Explanations](lemline-explain-event-driven.md) section for architectural insights
    3. Examine the [Examples Library](lemline-examples-order.md) for real-world patterns

* **As a DevOps Engineer**:
    1. Start with [Runner Configuration](lemline-howto-config.md) guides
    2. Review [Observability & Performance](lemline-observability-lifecycle.md) documentation
    3. Consult the [Reference](lemline-ref-config.md) section for detailed settings

### By Problem

* **Need to integrate with existing services?**
    - See how-to guides for [HTTP calls](lemline-howto-http.md), [OpenAPI](lemline-howto-openapi.md),
      and [gRPC](lemline-howto-grpc.md)

* **Concerned about error handling?**
    - Check out guides for [try/catch](lemline-howto-try-catch.md), [retries](lemline-howto-retry.md), and
      the [Error Propagation Model](lemline-explain-errors.md)

* **Want to understand performance characteristics?**
    - Read [Performance Comparison](lemline-observability-performance.md)
      and [Database I/O Analysis](lemline-observability-io.md)

## Documentation Enhancement

This documentation is continuously improved. If you find something missing or have suggestions, please see
our [Contribution Guide](lemline-community-contribution.md) to learn how you can help enhance the documentation.
