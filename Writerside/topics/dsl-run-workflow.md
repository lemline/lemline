---
title: Run Workflow
---

# Run Workflow Task (`run: workflow`)

## Purpose

The `run: workflow` task allows a workflow to invoke and execute another Serverless Workflow definition as a sub-workflow. This facilitates modular design, reusability, and the composition of complex processes from smaller, focused workflows.

## Usage Example

```yaml
document:
  dsl: '1.0.0'
  namespace: main_orchestration
  name: order-processing
  version: '1.1.0'
# Assume 'inventory-check' workflow exists in 'shared_services' namespace
do:
  - checkInventory:
      run:
        workflow:
          namespace: shared_services # Namespace of the workflow to run
          name: inventory-check      # Name of the workflow to run
          version: '1.0.0'           # Specific version to run
          # Pass required input to the sub-workflow
          input: 
            productId: "${ .order.itemId }"
            quantity: "${ .order.quantity }"
        # `await: true` (default) waits for the sub-workflow to complete
        # `return: stdout` (default) captures the sub-workflow's final output
        return: stdout 
      # Output of this task is the final output of 'inventory-check'
      then: processInventoryResult
  - processInventoryResult:
      # Input might be { available: true, stockLevel: 150 }
      switch:
        - if: "${ .available == true }"
          then: proceedToPayment
        - then: notifyOutOfStock # Default case
```

## Additional Examples

### Example: Running Latest Version

```yaml
do:
  - runLatestUtility:
      run:
        workflow:
          namespace: shared_utilities
          name: data-cleanup
          # version: 'latest' # Implicitly defaults to latest
          input: { targetDir: "${ .tempDirectory }" }
        return: stdout # Get the output of the latest cleanup workflow
      then: processCleanupResult
```

### Example: Starting Sub-Workflow Without Awaiting

```yaml
do:
  - triggerLongProcess:
      run:
        workflow:
          namespace: background_jobs
          name: monthly-report-generation
          version: '2.0'
          input: { month: "${ .currentMonth }", year: "${ .currentYear }" }
        # Start the sub-workflow but don't wait for it
        await: false 
      # Task output is its input, workflow continues immediately
      then: logReportTriggered
```

### Example: Handling Faulted Sub-Workflow

```yaml
do:
  - trySubWorkflow:
      try:
        run:
          workflow:
            namespace: experimental
            name: potentially-failing-job
            version: '0.1-beta'
            input: "${ . }"
          # `await: true` is default
          # `return: stdout` is default, but will fail if sub-workflow faults
      catch:
        # Catch any error from the sub-workflow execution itself
        errors: 
          with: { type: "https://serverlessworkflow.io/spec/1.0.0/errors/runtime" }
        as: runtimeError
        do:
          - logSubWorkflowFailure:
              call: logError
              with:
                message: "Sub-workflow experimental/potentially-failing-job faulted"
                # Note: Accessing the sub-workflow's specific error might require `return: all` or `return: stderr` in the `run` task and inspecting the result within the catch block.
                details: "${ $runtimeError }" 

```

### Example: Using `return: all` to Get Status and Output/Error

```yaml
do:
  - executeSubflowAndCheck:
      run:
        workflow:
          namespace: validation_service
          name: input-validator
          version: '1.3'
          input: { data: "${ .rawData }" }
        # Get status code, stdout (if completed), and stderr (if faulted)
        return: all 
      then: routeBasedOnSubflowResult
  - routeBasedOnSubflowResult:
      # Input: { "code": 0, "stdout": { "isValid": true }, "stderr": null } OR
      # Input: { "code": 1, "stdout": null, "stderr": { "type": "...", "title": "Validation Failed", ... } }
      switch:
        - if: "${ .code == 0 and .stdout.isValid == true }"
          then: processValidData
        - if: "${ .code == 0 and .stdout.isValid == false }"
          then: handleInvalidData
        - then: handleSubflowError # If .code is non-zero
```

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
        *   `type` (String): A URI identifying the error type (e.g., `https://serverlessworkflow.io/spec/1.0.0/errors/runtime`).
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