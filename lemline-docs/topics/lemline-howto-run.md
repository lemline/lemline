---
title: How to run scripts or containers
---

# How to run scripts or containers

Open Workflow defines a `run` task for shell commands, scripts, containers and child
workflows. See [Supported Features](lemline-ref-supported-features.md) for Lemline's
runtime support for each variant.

## Load an external script

Use `run.script.source.endpoint` to reference a script. Set `language` and pass
inputs as named `arguments`. Lemline reads the script from the file system of the runner
that executes the task: use a `file://` URI and place the script at that path on every runner.

<code-block lang="yaml" src="workflows/reference/load-external-script.yaml"/>

> **Runtime support:** Lemline accepts this definition but does not load external scripts yet. The task fails with `Unsupported endpoint type for script source`. Put the script inline in `run.script.code` to run it on Lemline.

## Run a container

Set `run.container.image` and, if needed, a `command` and `volumes`.
The referenced volume path has to be available to the executor.

<code-block lang="yaml" src="workflows/reference/run-container-with-volume.yaml"/>

> **Runtime support:** Lemline accepts this definition but has no container executor yet. The task fails with `Unknown run task type: RunContainer`.

## Invoke a child workflow

Identify the child by namespace, name and version. Use `run.workflow.input`
to supply named values for the child workflow. Register the child definition before
starting the parent:

<code-block lang="yaml" src="workflows/reference/shipping-quote.yaml"/>

The parent waits for the child and receives its output:

<code-block lang="yaml" src="workflows/reference/invoke-order-workflow.yaml"/>

Started with `{"orderId":"ORD-42"}`, the parent completes with the child's quote in `quote`.

See [Run Workflow](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#workflow-process) for awaiting completion and handling results.
