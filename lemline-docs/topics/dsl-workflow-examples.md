---
title: Workflow Definition Examples
---

# Workflow Definition Examples

## Introduction

This page provides a collection of practical examples showing different approaches to defining Open Workflows. Each example demonstrates specific use cases, patterns, and combinations of features that can be employed when creating workflow definitions.

These examples range from simple sequential task execution to complex event-driven workflows with parallel processing and error handling. They showcase how the various properties and constructs of the DSL can be combined to solve real-world orchestration challenges.

For a complete reference of all top-level workflow properties, please see [Workflow Definition Structure](dsl-workflow-definition.md).

Feel free to use these examples as starting points or templates for your own workflow definitions. You can modify and extend them to suit your specific requirements.

## Example 1: Basic Sequential Task Execution

This example demonstrates a straightforward workflow with minimal properties and sequential task execution. It represents a simple order processing flow with three consecutive function calls.

**Key features:**
- Proper `document` structure with required workflow properties
- Function definitions in the resource catalog (`use.functions`)
- Sequential task execution with explicit `then` directives
- Function calls with runtime expression arguments

<code-block lang="yaml" src="workflows/dsl-workflow-examples/example-1-basic-sequential-task-execution.yaml"/>

## Example 2: Event-Driven Customer Communication

A customer signup starts a workflow and supplies the signup payload. The workflow
chooses one notification channel based on the customer preference.

<code-block lang="yaml" src="workflows/dsl-workflow-examples/example-2-event-driven-workflow-with-resource-catalog.yaml"/>

## Example 3: Workflow with Parallel Execution and Error Handling

This example runs two checks on an order in parallel and falls back to a manual review if either of them fails.

**Key features:**
- Proper `document` structure with required workflow properties
- Parallel execution using the `fork` task
- Error handling with `try`/`catch` around the parallel branches
- A fallback result set in the `catch` block

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

## Example 4: Time-based Scheduled Workflow

This example shows a simple maintenance workflow that runs on a time-based schedule using cron syntax. It executes a cleanup container and then sends a report of the operation.

**Key features:**
- Proper `document` structure with required workflow properties
- Time-based scheduling with cron syntax
- Container execution with the `run` task
- Command line with its parameters
- Result handling from previous task executions

<code-block lang="yaml" src="workflows/dsl-workflow-examples/example-4-time-based-scheduled-workflow.yaml"/>

## Example 5: Input/Output Transformation Workflow

This example demonstrates a workflow with input transformation and output formatting. It reshapes the data it receives before the first task runs and returns only the fields it computed.

**Key features:**
- Proper `document` structure with required workflow properties
- Workflow-level input transformation with a runtime expression
- Workflow-level output transformation

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>
