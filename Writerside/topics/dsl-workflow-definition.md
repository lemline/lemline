---
title: Workflow Definition Structure
---

# Workflow Definition Structure

## Introduction

This page describes the top-level structure and properties of a Serverless Workflow definition file, typically written
in YAML. Understanding this overall structure is the first step in creating your own workflows.

A workflow definition file acts as the blueprint for your automated process, defining metadata, reusable resources,
scheduling information (if any), and the core execution logic.

## Top-Level Properties

A Serverless Workflow definition document consists of the following top-level properties:

* **`dsl`** (String, Required): Specifies the version of the Serverless Workflow DSL specification the document conforms
  to (e.g., `'1.0.0'`). This ensures the workflow engine interprets the syntax correctly.
* **`namespace`** (String, Required): Provides a logical grouping for the workflow, often used for organization,
  identification, and potentially scoping of resources or events within a runtime environment (e.g.,
  `'com.example.billing'`).
* **`name`** (String, Required): The unique name of the workflow within its namespace (e.g., `'process-invoice'`).
  `namespace` + `name` typically forms a unique workflow identifier.
* **`version`** (String, Required): The version of this specific workflow definition (e.g., `'1.0.2'`). This allows for
  versioning and evolution of workflows over time.
* **`title`** (String, Optional): A short, human-readable title for the workflow (e.g.,
  `'Invoice Processing Workflow'`).
* **`description`** (String, Optional): A more detailed human-readable description of the workflow's purpose.
* **`use`** (Object, Optional): Defines reusable resources and definitions used throughout the workflow. This includes
  things like function definitions, event definitions, authentication configurations, etc.
  See [Resource Catalog](dsl-resource-catalog.md) for details.
* **`do`** (Array<String: Task>, Required): Defines the core execution logic of the workflow as a sequence (or
  structure)
  of Tasks. This is where the main steps, control flow, and actions are specified.
* **`schedule`** (Object | Array&lt;Object\>, Optional): Defines how the workflow should be triggered based on time
  schedules or events. The following formats are supported:
    * **Time-based Schedule (Cron)**: Uses standard cron syntax to execute workflows on a time-based schedule.
      ```yaml
      schedule:
        cron: '0 0 * * *'  # Run daily at midnight
      ```
    * **Event-based Schedule**: Specifies an event that triggers the workflow.
      ```yaml
      schedule:
        - when:
            event: EventName  # References an event defined in use.events
          start: firstTaskName  # Optional: specifies which task to start with
      ```
    * **Multiple Trigger Schedules**: An array of scheduling conditions.
      ```yaml
      schedule:
        - cron: '0 0 * * *'  # Time-based trigger
        - when:               # Event-based trigger
            event: EventName
      ```

  > **Multiple Workflow Instances and Scheduling**
  >
  > When a workflow definition includes a `schedule` property and multiple instances of this workflow are started:
  >
  > - Each workflow instance maintains its own independent schedule
  > - For time-based schedules with `cron` or `every`, each instance will execute at the specified times, potentially
      resulting in parallel executions
  > - For event-based schedules, each instance will independently listen for and react to the specified events
  > - When using `after` scheduling (which runs the workflow again after completion), each instance manages its own
      restart cycle
  >
  > This behavior can lead to resource contention if not carefully managed. In production environments, consider
  > implementing instance coordination or limiting the number of concurrent instances to avoid unexpected behavior or
  > resource exhaustion.

* **`timeout`** (Object, Optional): Defines the maximum duration the entire workflow instance is allowed to execute
  before being timed out. This can prevent runaway workflows, resource leaks, or deadlock situations.
  ```yaml
  timeout:
    hours: 1
    minutes: 30  # Total timeout of 1 hour and 30 minutes
  ```
  See [Timeouts](dsl-timeouts.md) for more details on timeout configurations.
* **`evaluate`** (Object, Optional): Configures how runtime expressions are evaluated within the workflow. This allows
  customization of the expression language and evaluation mode.
  ```yaml
  evaluate:
    language: jq  # The language used for runtime expressions (defaults to 'jq')
    mode: strict  # Evaluation mode: 'strict' requires expressions to be enclosed in ${ }, 
                  # while 'loose' evaluates any value (defaults to 'strict')
  ```
  These settings affect how all runtime expressions throughout the workflow are processed.
* **`metadata`** (Object, Optional): An optional map of custom key-value pairs that can be used to attach arbitrary
  metadata to the workflow definition (e.g., author, team, deployment environment tags). These values are typically used
  for documentation, governance, or filtering workflows in management UIs, but don't affect runtime behavior.
  ```yaml
  metadata:
    author: "Jane Doe"
    team: "Order Processing Team"
    environment: "production"
    priority: "high"
    lastReviewDate: "2023-10-15"
  ```
* **`extensions`** (Array<String: Object>, Optional): Defines extensions that enhance or modify the behavior of tasks in
  the
  workflow. Extensions can be used to implement cross-cutting concerns like logging, monitoring, or mocking. Each
  extension consists of a name, specification for which tasks it extends, and the tasks to execute before and/or after
  the extended task.
  ```yaml
  extensions:
    - logging:
        extend: all  # Apply to all tasks
        before:
          - logTaskStart:
              # Task to run before each extended task
        after:
          - logTaskEnd:
              # Task to run after each extended task
  ```
* **`input`** (Object, Optional): Configures the validation and transformation of data coming into the workflow. This
  allows for ensuring the workflow only receives properly structured input and can transform it into the format required
  by the workflow.
  ```yaml
  input:
    schema: # JSON Schema for validating incoming data
      type: object
      required: ["orderId", "customerId"]
      properties:
        orderId: { type: string }
        customerId: { type: string }
        items: { type: array }
    from: "${ . | select(.items != null) }"  # Transform the input before processing
  ```
* **`output`** (Object, Optional): Configures the filtering and transformation of data that the workflow will return.
  This ensures the workflow produces consistent and properly formatted results.
  ```yaml
  output:
    as: "${ { orderId: .orderId, status: .status, processedItems: .items | length } }"
  ```

## Workflow Examples

The structure and use of these properties is best understood through examples. For a comprehensive set of workflow
definition examples covering various use cases and patterns, please refer to
the [Workflow Definition Examples](dsl-workflow-examples.md) page.

This examples page includes:

- Basic sequential task execution
- Event-driven workflows using the resource catalog
- Workflows with parallel execution and error handling
- Time-based scheduled workflows
- Input/output transformation workflows

Each example is annotated with explanations and highlights key features to help you understand how to effectively
structure your own workflow definitions. 