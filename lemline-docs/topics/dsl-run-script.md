---
title: Run Script
---

# Run Script Task (`run: script`)

## Purpose

The `run: script` task allows a workflow to execute a script written in a supported language (e.g., JavaScript, Python) directly within the runtime environment. This is useful for custom logic, data transformations, or simple integrations that don't warrant a full container or external function call.

## Usage Example

<code-block lang="yaml" src="workflows/dsl-run-script/usage-example.yaml"/>

## Additional Examples

### Example: Loading Script from External Source

<code-block lang="yaml" src="workflows/reference/load-external-script.yaml"/>

### Example: Using Python with Environment Variables

<code-block lang="yaml" src="workflows/dsl-run-script/example-using-python-with-environment-variables.yaml" include-lines="6-27"/>

### Example: Returning Exit Code

<code-block lang="yaml" src="workflows/dsl-run-script/example-returning-exit-code.yaml" include-lines="7-25"/>

## Configuration Options

The configuration is provided under the `run` property, specifically within the nested `script` object.

### `run` (Object, Required)

*   **`script`** (Object, Required): Defines the script process configuration.
    *   **`language`** (String, Required): The language of the script. Supported values are defined by the specification (e.g., `js`, `python`). Check the DSL reference for specific supported versions (e.g., `JavaScript ES2024`, `Python 3.13.x`). Using other versions might require the [Run Container Task](dsl-run-container.md).
    *   **`code`** (String, Conditionally Required): The inline source code of the script to execute. Required if `source` is not provided.
    *   **`source`** (Object, Conditionally Required): An object pointing to an external file containing the script source code. Required if `code` is not provided. It contains:
        *   `endpoint` (Object, Required): Defines the location of the script file.
            *   `uri` (String | Object, Required): The URI (string or URI template object) pointing to the script file.
            *   `authentication` (String | Object, Optional): Authentication details (inline or reference) needed to access the script file URI.
    *   **`arguments`** (Map<String, Any>, Optional): A key/value map defining arguments or variables made available to the script's execution environment. Values can be static or derived using [Runtime Expressions](dsl-runtime-expressions.md). How these are exposed depends on the language (e.g., global variables in JS, injected context in Python).
    *   **`environment`** (Map<String, String>, Optional): A key/value map of environment variables to set for the script execution process. Values can be static strings or evaluated from [Runtime Expressions](dsl-runtime-expressions.md).
*   **`await`** (Boolean, Optional, Default: `true`):
    *   `true`: The workflow task waits for the script process to complete before proceeding. The task's output is determined by the `return` property.
    *   `false`: The task starts the script and proceeds immediately. The task's `rawOutput` is its `transformedInput`.
*   **`return`** (String - `stdout` | `stderr` | `code` | `all` | `none`, Optional, Default: `stdout`): Specifies what the task's `rawOutput` should be when `await` is `true`.
    *   `stdout`: The standard output (stdout) stream of the script. *Note: Many scripting runtimes map the script's return value to stdout.*
    *   `stderr`: The standard error (stderr) stream (e.g., `console.error` or unhandled exceptions).
    *   `code`: The integer exit code (usually 0 for success).
    *   `all`: An object containing the results from the script process. It typically includes:
        *   `code` (Integer): The exit code of the script.
        *   `stdout` (String | Null): The content captured from the standard output stream.
        *   `stderr` (String | Null): The content captured from the standard error stream.
    *   `none`: The task produces no output (evaluates to `null`).

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `run.script.arguments` and `run.script.environment`.
*   The script itself typically accesses data passed via `run.script.arguments`.
*   The `rawOutput` depends on the `run.await` and `run.return` settings.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If `await` is `true` and the script process exits with a non-zero status code or throws an unhandled exception, a `Runtime` error is typically raised, and the `then` directive is *not* followed (unless caught by `Try`). 