---
title: Run Shell Command
---

# Run Shell Command Task (`run: shell`)

## Purpose

The `run: shell` task allows a workflow to execute a command line instruction using the host system's default shell (e.g., bash, sh, cmd, powershell). This is useful for simple system interactions, invoking command-line tools, or file system operations.

## Usage Example

```yaml
document:
  dsl: '1.0.0'
  namespace: shell_runs
  name: run-simple-shell
  version: '1.0.0'
do:
  - executeShellCommand:
      run:
        shell:
          command: 'echo "Processing file: ${fileName}" && grep -c "ERROR" "${filePath}' 
          # Pass arguments as environment variables
          environment: 
            fileName: "${ .inputFile.name }"
            filePath: "${ .inputFile.path }"
        # `await: true` (default) waits for completion
        # `return: stdout` (default) captures the standard output
        return: stdout 
      then: processShellOutput
  - processShellOutput:
      # Input might be "Processing file: data.log\n3" (if 3 errors found)
      set:
        # Example: extract the error count (implementation depends on shell/tools)
        errorCount: "${ parseInteger(split(. , '\n')[1]) }"

## Additional Examples

### Example: Capturing Standard Error

```yaml
do:
  - attemptRiskyOperation:
      run:
        shell:
          # Command might succeed (stdout) or fail (stderr)
          command: "/opt/tools/do-something --input /data/input.txt || echo 'Fallback value'"
        # Capture stderr instead of stdout
        return: stderr 
      then: handleOperationError
  - handleOperationError:
      # Input is the content sent to stderr, or empty if none
      set:
        errorMessage: "${ . }"
```

### Example: Capturing All Outputs

```yaml
do:
  - executeUtility:
      run:
        shell:
          command: "utility-tool --process ${ .fileId } --verbose"
        # Get exit code, stdout, and stderr together
        return: all 
      then: processUtilityResult
  - processUtilityResult:
      # Input: { "code": 0, "stdout": "...", "stderr": "..." }
      set:
        exitCode: "${ .code }"
        outputLog: "${ .stdout }"
        # Use stderr only if exit code was non-zero
        errorLog: "${ if .code != 0 then .stderr else null end }"
```

## Configuration Options

The configuration is provided under the `run` property, specifically within the nested `shell` object.

### `run` (Object, Required)

*   **`shell`** (Object, Required): Defines the shell command process configuration.
    *   **`command`** (String, Required): The shell command line to execute. This string is typically passed directly to the system's shell interpreter.
    *   **`arguments`** (Map<String, Any>, Optional): A key/value map defining arguments passed to the shell command. *Note: How these are passed (e.g., as command-line arguments appended to `command`, or made available in the environment) might depend on the runtime implementation. Using `environment` is often more explicit.*
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

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `run.shell.environment`, `run.shell.arguments`, and `run.shell.command`.
*   The shell command typically accesses dynamic data via environment variables (set by `run.shell.environment`) or command-line arguments constructed within `run.shell.command`.
*   The `rawOutput` depends on the `run.await` and `run.return` settings.
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If `await` is `true` and the shell command exits with a non-zero status code, a `Runtime` error is typically raised, and the `then` directive is *not* followed (unless caught by `Try`). 