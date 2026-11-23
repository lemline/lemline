---
title: Run Workflow
---

# Run Workflow Task (`run: workflow`)

## Purpose

The `run: workflow` task allows a workflow to invoke and execute another Open Workflow definition as a sub-workflow. This facilitates modular design, reusability, and the composition of complex processes from smaller, focused workflows.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-run-workflow/usage-example.yaml"/>

## Additional Examples

### Example: Running Latest Version

<code-block lang="yaml" src="workflows/dsl-run-workflow/example-running-latest-version.yaml" include-lines="7-16"/>

### Example: Starting Sub-Workflow Without Awaiting

<code-block lang="yaml" src="workflows/dsl-run-workflow/example-starting-sub-workflow-without-awaiting.yaml" include-lines="6-17"/>

### Example: Handling Faulted Sub-Workflow

<code-block lang="yaml" src="workflows/dsl-run-workflow/example-handling-faulted-sub-workflow.yaml" include-lines="7-27"/>

### Example: Using `return: all` to Get Status and Output/Error

<code-block lang="yaml" src="workflows/dsl-run-workflow/example-using-return-all-to-get-status-and-output-error.yaml" include-lines="7-26"/>

## Configuration Options

The configuration is provided under the `run` property, specifically within the nested `workflow` object.

### `run` (Object, Required)

*   **`workflow`** (Object, Required): Defines the sub-workflow execution configuration.
    *   **`namespace`** (String, Required): The namespace where the target workflow definition resides.
    *   **`name`** (String, Required): The name of the target workflow definition to execute.
    *   **`version`** (String, Optional, Default: `latest`): The specific version of the target workflow definition to execute. If omitted, the runtime typically uses the version marked as `latest`.
    *   **`input`** (Any, Optional): The input data to pass to the sub-workflow when it starts. This data is processed by the sub-workflow's own `input.from` and validated against its `input.schema`, if defined.
*   **`await`** (Boolean, Optional, Default: `true`):
    *   `true`: The parent workflow task waits for the sub-workflow process to reach a terminal state (e.g., `completed`, `faulted`, `cancelled`) before proceeding. The task's output is determined by the `return` property based on the sub-workflow's outcome.
    *   `false`: The task initiates the sub-workflow and proceeds immediately without waiting for its completion. The task's `rawOutput` is its `transformedInput`.
*   **`return`** (String - `stdout` | `stderr` | `code` | `all` | `none`, Optional, Default: `stdout`): Specifies what the task's `rawOutput` should be when `await` is `true`.
    *   `stdout`: The final output data of the sub-workflow (i.e., the result after its top-level `output.as` transformation), but only if it completed successfully.
    *   `stderr`: The WorkflowError object if the sub-workflow faulted. This object typically includes:
        *   `type` (String): A URI identifying the error type (e.g., `https://open-workflow-specification.org/spec/1.0.0/errors/runtime`).
        *   `status` (Integer): An appropriate status code (often mirroring HTTP status codes).
        *   `instance` (String): A JSON Pointer indicating the component within the sub-workflow where the error originated.
        *   `title` (String, Optional): A short, human-readable summary.
        *   `detail` (String, Optional): A more detailed explanation.
    *   `code`: A representation of the sub-workflow's terminal status (e.g., 0 for `completed`, non-zero for `faulted`/`cancelled` - specific codes may vary by runtime).
    *   `all`: An object containing the sub-workflow's status (`code`), final output (`stdout`), and error information (`stderr` if faulted).
    *   `none`: The task produces no output (evaluates to `null`).

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the `run: workflow` task is available for use within runtime expressions when defining the `run.workflow.input`.
*   The `run.workflow.input` becomes the initial raw input for the sub-workflow.
*   The `rawOutput` of the `run: workflow` task depends on the `run.await` and `run.return` settings, reflecting the outcome and final data of the sub-workflow.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If `await` is `true` and the sub-workflow terminates in a `faulted` or `cancelled` state, a `Runtime` error (potentially containing the sub-workflow's error details in its `stderr` component if `return: all` or `return: stderr` is used) is typically raised by the `run: workflow` task, and the `then` directive is *not* followed (unless caught by `Try`). 