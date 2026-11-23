---
title: Run Container
---

# Run Container Task (`run: container`)

## Purpose

The `run: container` task allows a workflow to execute an external process encapsulated within a containerized environment (e.g., Docker). This is useful for running complex applications, specific versions of tools, or any process that benefits from isolation and reproducible environments.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-run-container/usage-example.yaml"/>

## Additional Examples

### Example: Using Environment Variables and Volumes

<code-block lang="yaml" src="workflows/dsl-run-container/example-using-environment-variables-and-volumes.yaml" include-lines="6-21"/>

### Example: Getting All Process Results

<code-block lang="yaml" src="workflows/dsl-run-container/example-getting-all-process-results.yaml" include-lines="6-20"/>

### Example: Running Without Awaiting (Fire and Forget)

<code-block lang="yaml" src="workflows/dsl-run-container/example-running-without-awaiting-fire-and-forget.yaml" include-lines="6-17"/>

## Configuration Options

The configuration is provided under the `run` property, specifically within the nested `container` object.

### `run` (Object, Required)

*   **`container`** (Object, Required): Defines the container process configuration.
    *   **`image`** (String, Required): The name and tag of the container image to run (e.g., `ubuntu:latest`, `my-custom-app:1.2`).
    *   **`name`** (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md) evaluated to assign a specific name to the running container instance. Useful for identification and management. Runtimes may have a default naming convention (e.g., `{workflow.name}-{uuid}.{workflow.namespace}-{task.name}`).
    *   **`command`** (String, Optional): The command and its arguments to execute inside the container. If not specified, the image's default command (e.g., `ENTRYPOINT` or `CMD` in a Dockerfile) is used.
    *   **`ports`** (Map<String, String | Integer>, Optional): Defines port mappings between the host and the container (e.g., `{"8080": "80/tcp"}`). The exact format and capabilities may depend on the container runtime.
    *   **`volumes`** (Map<String, String>, Optional): Defines volume mappings between the host and the container (e.g., `{"/host/data": "/container/data:ro"}`). The exact format and capabilities may depend on the container runtime.
    *   **`environment`** (Map<String, String>, Optional): A key/value map of environment variables to set inside the container. Values can be static strings or evaluated from [Runtime Expressions](dsl-runtime-expressions.md).
    *   **`lifetime`** (Object, Optional): Configures the container's lifecycle management after execution. Contains:
        *   `cleanup` (String, Required, Default: `never`): The cleanup policy. Supported values:
            *   `always`: The container is deleted immediately after execution.
            *   `never`: The runtime should never delete the container.
            *   `eventually`: The container is deleted after the duration specified in `after`.
        *   `after` (String | Object, Optional): The duration after execution to wait before deleting the container. Required if `cleanup` is `eventually`. Can be an ISO 8601 duration string or a [Duration Object](TODO: Link to Duration object page if exists).
*   **`await`** (Boolean, Optional, Default: `true`):
    *   `true`: The workflow task waits for the container process to complete before proceeding. The task's output is determined by the `return` property.
    *   `false`: The task starts the container and proceeds immediately without waiting for completion. The task's `rawOutput` is its `transformedInput`.
*   **`return`** (String - `stdout` | `stderr` | `code` | `all` | `none`, Optional, Default: `stdout`): Specifies what the task's `rawOutput` should be when `await` is `true`.
    *   `stdout`: The standard output (stdout) stream of the container process.
    *   `stderr`: The standard error (stderr) stream of the container process.
    *   `code`: The integer exit code of the container process.
    *   `all`: An object containing the results from the process. It typically includes:
        *   `code` (Integer): The exit code of the process.
        *   `stdout` (String | Null): The content captured from the standard output stream.
        *   `stderr` (String | Null): The content captured from the standard error stream.
    *   `none`: The task produces no output (evaluates to `null`).

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `run.container.environment` and potentially `run.container.name` or `run.container.command`.
*   The `rawOutput` depends on the `run.await` and `run.return` settings. If `await` is `false`, output is the `transformedInput`. Otherwise, it's determined by `return`.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If `await` is `true` and the container process exits with a non-zero status code (indicating an error), a `Runtime` error is typically raised, and the `then` directive is *not* followed (unless caught by `Try`). If `await` is `false`, process errors are generally not automatically caught by the workflow task itself. 