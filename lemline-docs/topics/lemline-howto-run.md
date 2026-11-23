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

## Run a container

Set `run.container.image` and, if needed, a `command` and `volumes`.
The referenced volume path has to be available to the executor.

<code-block lang="yaml" src="workflows/reference/run-container-with-volume.yaml"/>

## Invoke a child workflow

Identify the child by namespace, name and version. Use `run.workflow.input`
to supply named values for the child workflow.

<code-block lang="yaml" src="workflows/reference/invoke-order-workflow.yaml"/>

See [Run Workflow](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#workflow-process) for awaiting completion and handling results.
