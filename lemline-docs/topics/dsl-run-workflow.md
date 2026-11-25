---
title: Run Workflow
---

# Run Workflow Task (`run: workflow`)

Use `run.workflow` to start another workflow. This lets a parent reuse a child workflow that has its own
inputs, outputs, and execution history. Register both definitions before starting the parent.

## Define the Child

This child checks that a requested quantity is positive, reads the shop’s stock, and returns its decision.

<code-block lang="yaml" src="workflows/dsl-run-workflow/child-stock-check.yaml"/>

## Await the Child Output

The parent selects a child by namespace, name, and version. It passes a quantity as input and awaits the child’s result.

<code-block lang="yaml" src="workflows/dsl-run-workflow/parent-stock-check.yaml"/>

## Start Without Awaiting

With `await: false`, the parent can complete while the child still runs. The run task passes its input onward.

<code-block lang="yaml" src="workflows/dsl-run-workflow/parent-submit-stock-check.yaml"/>

## Handle a Child Fault

This parent catches the error raised by the child when the quantity is zero.

<code-block lang="yaml" src="workflows/dsl-run-workflow/parent-handle-stock-error.yaml"/>

## Select the Latest Version

The DSL uses `version: latest` to select the current version when starting the child.

<code-block lang="yaml" src="workflows/dsl-run-workflow/parent-latest-stock-check.yaml"/>

> **Lemline**
>
> The worker currently looks for a definition with the literal version `latest`. It logs that the
> child definition was not found and the waiting parent does not complete. Use an explicit version, as in
> `parent-stock-check`. [Issue #122](https://github.com/lemline/lemline/issues/122) tracks version resolution.

## Request All Process Results

For an awaited process, `return: all` asks for the exit code and the standard result and error streams in one object.

<code-block lang="yaml" src="workflows/dsl-run-workflow/parent-stock-result.yaml"/>

> **Lemline**
>
> For a child workflow, `return: all` currently returns the child’s output directly. A child
> fault propagates as an error and can be caught in the parent. [Issue #123](https://github.com/lemline/lemline/issues/123) tracks the result format.

## Configuration Options

| Property | Purpose |
|---|---|
| `run.workflow.namespace` | Namespace of the child definition. |
| `run.workflow.name` | Name of the child definition. |
| `run.workflow.version` | Version of the child definition, written as a string. |
| `run.workflow.input` | Child input. The child applies its own schema and transformations. |
| `run.await` | Whether to wait for the child. Defaults to `true`. |
| `run.return` | Which process result to return when awaiting completion. |

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these commands from the setup directory. Compare the parent and child results in the worker terminal.

```bash
bin/lemline definition post -f child-stock-check.yaml
bin/lemline instance start examples child-stock-check --input '{"quantity":1}'
bin/lemline definition post -f parent-stock-check.yaml
bin/lemline instance start examples parent-stock-check --input '{"quantity":2}'
bin/lemline definition post -f parent-submit-stock-check.yaml
bin/lemline instance start examples parent-submit-stock-check --input '{"quantity":3}'
bin/lemline definition post -f parent-handle-stock-error.yaml
bin/lemline instance start examples parent-handle-stock-error --input '{"quantity":0}'
bin/lemline definition post -f parent-stock-result.yaml
bin/lemline instance start examples parent-stock-result --input '{"quantity":4}'
bin/lemline definition post -f parent-latest-stock-check.yaml
bin/lemline instance start examples parent-latest-stock-check --input '{"quantity":5}'
sleep 20
```

| Example | Expected result |
|---|---|
| `child-stock-check` | `{"quantity":1,"canReserve":true}` |
| `parent-stock-check` | Awaits its child and returns `{"canReserve":true}`. |
| `parent-submit-stock-check` | Returns `{"submittedQuantity":3}`. The child independently returns `{"quantity":3,"canReserve":true}`. |
| `parent-handle-stock-error` | The child faults. The parent returns `{"status":422,"reason":"Quantity must be positive"}`. |
| `parent-stock-result` | Lemline returns the child’s raw output, `{"quantity":4,"canReserve":true}`. |
| `parent-latest-stock-check` | The worker logs `Workflow child-stock-check (version=latest) not found` and the waiting parent produces no result. |

Use the worker terminal to check the four runnable parents and the lookup error for the final example. When those results are visible, stop the shop from the source checkout.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
