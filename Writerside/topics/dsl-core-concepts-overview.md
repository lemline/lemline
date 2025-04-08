---
title: Core Concepts Overview
---

# Core Concepts Overview

## Introduction

Before diving into specific workflow tasks, it's essential to understand the fundamental concepts that underpin the Serverless Workflow DSL. These core concepts define how data moves, how dynamic values are evaluated, how execution proceeds, and how errors are managed within a workflow.

Mastering these concepts will enable you to design more effective, robust, and maintainable workflows.

## Concepts Covered

This section covers the following critical mechanisms:

*   **[Data Flow](dsl-data-flow.md):** Explains how data is initialized, manipulated, passed between tasks, and ultimately influences the workflow's outcome. Covers the workflow context, task inputs/outputs, and data filtering/transformation.
*   **[Runtime Expressions](dsl-runtime-expressions.md):** Details the syntax and usage of expressions evaluated *during* workflow execution. These are crucial for accessing data, making decisions, and dynamically configuring tasks based on the current context.
*   **[Flow Control](dsl-flow-control.md):** Describes the mechanisms that dictate the sequence of execution, including conditional branching (`Switch`), looping (`For`), parallel execution (`Fork`), and sequential progression (`Do`, `then`).
*   **[Error Handling](dsl-error-handling.md):** Covers how workflows detect, manage, and recover from errors using mechanisms like `Try/Catch` blocks, standard error types, and custom error definitions (`Raise`).
*   **[Lifecycle Events](dsl-lifecycle-events.md):** Explains the standardized events emitted during workflow and task execution, providing observability into state changes and enabling monitoring, auditing, and event-driven reactions to workflow status.

Understanding these building blocks provides the foundation necessary to effectively utilize the various [Tasks](dsl-flow-overview.md) and [Event](dsl-events-overview.md) handling capabilities of the DSL. 