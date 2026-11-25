---
title: How to run scripts or containers
---

# How to run scripts or containers

Open Workflow defines a `run` task for shell commands, scripts, containers and child
workflows. See [Supported Features](lemline-ref-supported-features.md) for Lemline's
runtime support for each variant.

## Load an external script

Use `run.script.source.endpoint` to reference a script and set `language` to `js` or
`python`. Lemline reads the script from the file system of the runner that executes the
task: use a `file://` URI and place the script at that path on every runner. Any other
scheme, such as `https://`, faults the task with a `configuration` error, and so does a
file that does not exist.

The endpoint is either a plain string or an object with a `uri` property. Both accept a
runtime expression, for example `endpoint: ${ .scriptUri }`.

<code-block lang="yaml" src="workflows/reference/load-external-script.yaml"/>

Lemline passes `arguments` to the script as command-line arguments, in the order they are
written: each name, followed by its value unless the value is empty. In the example above
the script receives `email` and then the address, so a Python script reads the address
from `sys.argv[2]`. Name an argument `--email` to get the usual `--email <address>` form.
Values are runtime expressions and have to evaluate to a string or another scalar.

The runner needs Node.js 22 or later for `js` scripts and Python 3.8 or later for `python`
scripts.

## Run a container

Set `run.container.image` and, if needed, a `command` and `volumes`.
The referenced volume path has to be available to the executor.

<code-block lang="yaml" src="workflows/reference/run-container-with-volume.yaml"/>

## Invoke a child workflow

Identify the child by namespace, name and version. Use `run.workflow.input`
to supply named values for the child workflow.

<code-block lang="yaml" src="workflows/reference/invoke-order-workflow.yaml"/>

See [Run Workflow](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#workflow-process) for awaiting completion and handling results.
