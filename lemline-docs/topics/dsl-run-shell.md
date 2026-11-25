---
title: Run Shell Command
---

# Run Shell Command Task (`run: shell`)

## Purpose

The `run: shell` task invokes a command on the host that runs the workflow. Use it for command-line tools and simple file or system operations.

## Usage Example

Pass an order ID through an environment variable and capture standard output.

<code-block lang="yaml" src="workflows/dsl-run-shell/run-simple-shell.yaml"/>

## Additional Examples

### Example: Capturing Standard Error

The command writes a diagnostic to standard error and exits successfully.

<code-block lang="yaml" src="workflows/dsl-run-shell/shell-capture-stderr.yaml"/>

### Example: Capturing All Outputs

Pass the receipt message as a single argument and capture both streams with the exit code.

<code-block lang="yaml" src="workflows/dsl-run-shell/shell-capture-all.yaml"/>

## Configuration Options

The configuration is provided under the `run` property, specifically within the nested `shell` object.

### `run` (Object, Required)

*   **`shell`** (Object, Required): Defines the shell command process configuration.
    *   **`command`** (String, Required): The command to execute.
    *   **`arguments`** (List of strings, Optional): An ordered list appended to the command, for example `["-n", "Hello world"]`. Entries can use [Runtime Expressions](dsl-runtime-expressions.md).
    *   **`stdin`** (String, Optional): Standard input passed to the process, either a literal string or a runtime expression. Input is closed after the supplied content, including when no content is supplied.
    *   **`environment`** (Map<String, String>, Optional): A key/value map of environment variables to set specifically for the execution of this shell command. Values can be static strings or evaluated from [Runtime Expressions](dsl-runtime-expressions.md). This is a common way to pass dynamic data to the command.
*   **`await`** (Boolean, Optional, Default: `true`):
    *   `true`: The workflow task waits for the shell command process to complete before proceeding. The task's output is determined by the `return` property.
    *   `false`: The task starts the shell command and proceeds immediately. The task's `rawOutput` is its `transformedInput`.
*   **`return`** (String - `stdout` | `stderr` | `code` | `all` | `none`, Optional, Default: `stdout`): Specifies what the task's `rawOutput` should be when `await` is `true`.
    *   `stdout`: The standard output (stdout) stream of the command.
    *   `stderr`: The standard error (stderr) stream of the command.
    *   `code`: The integer exit code of the command (usually 0 for success).
    *   `all`: An object containing the results from the shell process. It typically includes:
        *   `code` (Integer): The exit code of the command.
        *   `stdout` (String | Null): The content captured from the standard output stream.
        *   `stderr` (String | Null): The content captured from the standard error stream.
    *   `none`: The task produces no output (evaluates to `null`).

> **Lemline**
>
> The worker starts the executable directly. Use `command: sh` with `-c` and the command string as separate arguments when you need shell expansion or redirection.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `run.shell.environment`, `run.shell.arguments`, and `run.shell.command`.
*   The shell command typically accesses dynamic data via environment variables (set by `run.shell.environment`) or command-line arguments constructed within `run.shell.command`.
*   The `rawOutput` depends on the `run.await` and `run.return` settings.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
> **Lemline**
>
> With `return: code` or `return: all`, the task returns the command’s exit status. A following task can use that status to choose how to proceed.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Run these examples with the worker on Linux or macOS, where a POSIX shell is available.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f run-simple-shell.yaml
bin/lemline instance start examples run-simple-shell --input '{"orderId":"O-100"}'
bin/lemline definition post -f shell-capture-stderr.yaml
bin/lemline instance start examples shell-capture-stderr
bin/lemline definition post -f shell-capture-all.yaml
bin/lemline instance start examples shell-capture-all --input '{"message":"receipt ready"}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `run-simple-shell` | `{"orderId":"O-100"}` |
| `shell-capture-stderr` | `{"diagnostic":"stock check skipped"}` |
| `shell-capture-all` | `{"code":0,"stdout":"receipt ready","stderr":"sample run"}` |
