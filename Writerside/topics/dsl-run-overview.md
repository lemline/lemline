---
title: Run Tasks
---

# Run Tasks Overview

## Purpose

The `run` task type in the Serverless Workflow DSL provides a powerful mechanism for executing external processes from within a workflow. This allows workflows to leverage existing scripts, command-line tools, containerized applications, or even trigger other workflows.

This capability is essential for:

*   Integrating with legacy systems or tools that don't have modern APIs.
*   Performing complex computations or data processing using specialized environments (containers).
*   Executing custom logic written in various scripting languages.
*   Orchestrating larger processes by breaking them down into reusable sub-workflows.

## Types of Run Tasks

The specific type of process to execute is determined by the nested object provided under the `run` property:

*   **`run: container`**:
    *   Executes a command within a specified container image (e.g., Docker).
    *   Ideal for running applications with specific dependencies or isolated environments.
    *   See: [Run Container Task](dsl-run-container.md)

*   **`run: script`**:
    *   Executes inline code or an external script file using a supported scripting language interpreter (e.g., JavaScript, Python) available in the runtime.
    *   Useful for custom logic, data manipulation, and simple integrations.
    *   See: [Run Script Task](dsl-run-script.md)

*   **`run: shell`**:
    *   Executes a command line instruction using the host system's default shell.
    *   Suitable for simple system interactions and invoking command-line utilities.
    *   See: [Run Shell Command Task](dsl-run-shell.md)

*   **`run: workflow`**:
    *   Invokes and executes another Serverless Workflow definition as a sub-workflow.
    *   Enables modular design and composition of complex processes.
    *   See: [Run Workflow Task](dsl-run-workflow.md)

## Common Concepts

While each run type executes a different kind of process, they share some common configuration options under the main `run` property:

*   **`await`**: Determines if the workflow task should wait for the external process to complete before proceeding.
*   **`return`**: Specifies what information from the completed process (e.g., standard output, standard error, exit code) should become the output of the workflow task (only applicable when `await` is true).

Please refer to the individual task pages linked above for detailed configuration options, examples, and specific behaviors related to each process type. 