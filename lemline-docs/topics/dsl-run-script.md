---
title: Run Script
---

# Run Script Task (`run: script`)

## Purpose

The `run: script` task allows a workflow to execute a script written in a supported language (e.g., JavaScript, Python) directly within the runtime environment. This is useful for custom logic, data transformations, or simple integrations that don't warrant a full container or external function call.

## Usage Example

The script prints the order ID passed by the workflow.

<code-block lang="yaml" src="workflows/dsl-run-script/run-simple-script.yaml"/>

## Additional Examples

### Example: Loading Script from External Source

Use `source.endpoint` to load a script outside the definition.

> **Lemline**
>
> Place the file at the same absolute path on every worker that runs the task. Lemline accepts a `file://` URI for the source.

<code-block lang="yaml" src="workflows/reference/load-external-script.yaml"/>

### Example: Using Python with Environment Variables

<code-block lang="yaml" src="workflows/dsl-run-script/script-read-environment.yaml"/>

### Example: Returning Exit Code

<code-block lang="yaml" src="workflows/dsl-run-script/script-exit-code.yaml"/>

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

> **Lemline**
>
> The worker passes each named argument followed by its value on the command line. Node.js 22+ and Python 3.8+ must be available on the worker for the respective languages.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
With `return: code`, downstream tasks can use the exit status to decide how to proceed.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run the worker on Linux or macOS with Node.js 22+ and Python 3.8+ on its path. On that machine, save
the Python file where `load-external-script` can find it.

```bash
mkdir -p /tmp/lemline-scripts
cat > /tmp/lemline-scripts/email-validator.py <<'EOF'
import re
import sys

args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
valid = re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", args["email"])
print("valid" if valid else "invalid")
EOF
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f run-simple-script.yaml
bin/lemline instance start examples run-simple-script --input '{"orderId":"O-100"}'
bin/lemline definition post -f load-external-script.yaml
bin/lemline instance start examples load-external-script --input '{"email":"ada@example.com"}'
bin/lemline instance start examples load-external-script --input '{"email":"not-an-email"}'
bin/lemline definition post -f script-read-environment.yaml
bin/lemline instance start examples script-read-environment --input '{"items":["SKU-1","SKU-2"]}'
bin/lemline definition post -f script-exit-code.yaml
bin/lemline instance start examples script-exit-code --input '{"orderId":"O-100"}'
bin/lemline instance start examples script-exit-code --input '{"orderId":"bad-id"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `run-simple-script` | `"Received O-100"` |
| `load-external-script` with `{"email":"ada@example.com"}` | `"valid"` |
| `load-external-script` with `{"email":"not-an-email"}` | `"invalid"` |
| `script-read-environment` | `{"count":2}` |
| `script-exit-code` with `{"orderId":"O-100"}` | `0` |
| `script-exit-code` with `{"orderId":"bad-id"}` | `1` |
