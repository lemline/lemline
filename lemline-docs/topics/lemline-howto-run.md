---
title: How to run scripts or containers
---

# How to run scripts or containers

This guide explains how to execute scripts, run containers, and invoke other workflows using the `run` task in Lemline. You'll learn how to integrate external execution environments with your workflows.

## Understanding the Run Task

The `run` task allows you to execute code or workflows outside the main workflow execution environment:

- **Scripts**: Execute shell scripts, Python, JavaScript, or other scripting languages
- **Containers**: Run Docker containers with specific images, commands, and environment variables
- **Workflows**: Invoke other workflows as subprocesses

This capability is useful for:
- Complex data processing that's easier to express in a traditional programming language
- Leveraging existing tools and libraries
- Integrating with systems that have command-line interfaces
- Running isolated workloads in containers
- Composing workflows for better modularity

## Running Shell Scripts

### Basic Shell Script Execution

The simplest way to run a shell script is:

```yaml
  - ExecuteScript:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "echo 'Hello, World!'"
      then: ProcessResult
```

### Script with Input Data

You can pass data to the script:

```yaml
  - ProcessData:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "echo \"Processing data for: $CUSTOMER_ID\""
      then: NextTask
```

### Multiline Scripts

For more complex scripts, you can use multiline YAML:

```yaml
  - AnalyzeData:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - |
              #!/bin/bash
              echo "Starting analysis..."

              # Process the input file
              grep "${PATTERN}" "${INPUT_FILE}" > "${OUTPUT_FILE}"

              # Count the results
              COUNT=$(wc -l < "${OUTPUT_FILE}")
              echo "Found $COUNT matching entries"

              # Return the result
              echo "{\"count\": $COUNT, \"outputFile\": \"${OUTPUT_FILE}\"}"
      then: ProcessResults
```

### Capturing Script Output

Script output (stdout) is captured and available in the task result:

```yaml
  - GetSystemInfo:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "uname -a && df -h && free -m"
      then: ProcessSystemInfo
  - ProcessSystemInfo:
      set:
        systemInfoOutput: ${ . }
        systemInfoLines: "${ . | split('\n') }"
        message: '${ "Captured system information: \(.systemInfoLines) | length lines" }'
      then: NextTask
```

### Running Python Scripts

You can execute Python scripts:

```yaml
  - RunPythonScript:
      run:
        shell:
          command: "python3"
          arguments:
            - "-c"
            - |
              import json
              import sys

              # Get input from environment variable
              input_data = json.loads(sys.environ['INPUT_JSON'])

              # Process the data
              result = {
                  'processed': True,
                  'count': len(input_data['items']),
                  'total': sum(item['price'] for item in input_data['items'])
              }

              # Output JSON result
              print(json.dumps(result))
      then: ProcessPythonResult
```

### Running External Script Files

You can also run external script files:

```yaml
  - RunExternalScript:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "/path/to/scripts/process_data.sh"
      then: HandleScriptResult
```

## Running Docker Containers

### Basic Container Execution

To run a Docker container:

```yaml
  - RunContainer:
      run:
        container:
          image: "alpine:latest"
          command: "echo"
          arguments:
            - "Hello from container"
      then: ProcessContainerResult
```

### Container with Environment Variables

Pass environment variables to the container:

```yaml
  - ProcessInContainer:
      run:
        container:
          image: "python:3.9-slim"
          command: "python"
          arguments:
            - "-c"
            - "import os; print(f'Processing data for: {os.environ[\"CUSTOMER_ID\"]}')"
          environment:
            CUSTOMER_ID: ${ .customerId }
      then: NextTask
```

### Container with Volume Mounts

Mount volumes for persistent storage:

```yaml
  - AnalyzeDataInContainer:
      run:
        container:
          image: "data-processor:1.0"
          command: "python"
          volumes:
            ".inputDirectory": "/data"
          arguments:
            - "/app/analyze.py"
            - "--input"
            - "/data/input.csv"
            - "--output"
            - "/data/output.json"
      then: ProcessResults
```

### Container with Resource Limits

Configure CPU and memory limits on the container host. The SDK 1.0.3 task schema accepts the command and arguments shown below.

```yaml
  - ResourceIntensiveTask:
      run:
        container:
          image: "data-processor:1.0"
          command: "python"
          arguments:
            - "/app/process_big_data.py"
      then: NextTask
```

### Container Networking

Configure network membership on the container host so that the container can resolve the service name.

```yaml
  - NetworkedContainer:
      run:
        container:
          image: "network-tool:latest"
          command: "ping"
          arguments:
            - "-c"
            - "4"
            - "service-b"
      then: ProcessResult
```

### Container Cleanup Policies

Control when containers are removed:

```yaml
  - TemporaryContainer:
      run:
        container:
          image: "data-processor:1.0"
          command: "python"
          lifetime: { cleanup: always }
          arguments:
            - "/app/process.py"
      then: NextTask
  - PersistentContainer:
      run:
        container:
          image: "debug-tools:latest"
          command: "sleep"
          lifetime: { cleanup: eventually, after: PT1H }
          arguments:
            - "3600"
      then: InspectContainer
```

## Running Other Workflows

### Invoking a Subworkflow

You can run another workflow as a subflow:

```yaml
  - InvokeSubworkflow:
      run:
        workflow:
          namespace: examples
          name: "data-validation"
          version: 1.0.0
          input:
            customerId: ${ .customerId }
            orderData: ${ .orderData }
      then: ProcessSubworkflowResult
```

### Handling Subworkflow Results

The results of the subworkflow are available in the task output:

```yaml
  - ProcessSubworkflowResult:
      set:
        validationPassed: ${ .result.valid }
        validationErrors: ${ .result.errors }
        message: ${ if .validationPassed then "Validation passed" else "Validation failed with " + (.validationErrors |
          length | tostring) + " errors" end }
      then: NextTask
```

### Dynamic Subworkflow Selection

You can dynamically select which subworkflow to run:

```yaml
  - DetermineWorkflow:
      set:
        workflowToRun: ${ if .orderType == "STANDARD" then "standard-order-process" else "premium-order-process" end }
        workflowVersion: "1.0"
      then: InvokeDynamicWorkflow
  - InvokeDynamicWorkflow:
      run:
        workflow:
          namespace: examples
          name: ".workflowToRun"
          version: .workflowVersion.0
          input:
            orderId: ${ .orderId }
            customerData: ${ .customerData }
      then: ProcessWorkflowResult
```

## Capturing and Using Output

### Return Models

You can control what output is captured from the execution:

```yaml
  - AnalyzeWithOptions:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "echo 'Standard output' && echo 'Error message' >&2 && exit 2"
        return: "all"
      then: ProcessCompleteOutput
  - ProcessCompleteOutput:
      set:
        stdout: ${ .stdout }
        stderr: ${ .stderr }
        exitCode: ${ .exitCode }
        success: ${ .exitCode == 0 }
      then: NextTask
```

Options for `return` include:
- `stdout` (default): Capture only standard output
- `stderr`: Capture only standard error
- `code`: Capture only the exit code
- `all`: Capture stdout, stderr, and exit code
- `none`: Don't capture any output

### Parsing JSON Output

If your script or container outputs JSON, it's automatically parsed:

```yaml
  - GenerateJsonData:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "echo '{\"status\": \"success\", \"count\": 42, \"items\": [1, 2, 3]}'"
      then: ProcessJsonOutput
  - ProcessJsonOutput:
      set:
        status: ${ .status }
        count: ${ .count }
        items: ${ .items }
        firstItem: ${ .items[0] }
      then: NextTask
```

### Error Handling and Exit Codes

Handle errors using the exit code:

```yaml
  - RunWithErrorHandling:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - ${ if [[ $RANDOM -lt 16000 ]]; then echo "Success"; else echo "Failed" >&2; exit 1; fi }
        return: "all"
      then: CheckResult
  - CheckResult:
      switch:
        - case1:
            then: HandleSuccess
            when: ${ .exitCode == 0 }
        - default:
            then: HandleError
  - HandleSuccess:
      set:
        status: "SUCCESS"
        message: '${ "Script executed successfully: \(.stdout)" }'
      then: Continue
  - HandleError:
      set:
        status: "ERROR"
        message: '${ "Script failed with: \(.stderr)" }'
        exitCode: ${ .exitCode }
      then: ErrorRecovery
```

## Advanced Run Task Features

### Using Try/Catch with Run Tasks

Wrap `run` tasks in `try` blocks for robust error handling:

```yaml
  - AttemptExecution:
      try:
        - ExecuteScript:
            run:
              shell:
                command: "/bin/bash"
                arguments:
                  - "-c"
                  - ${ ./process_data.sh .inputFile }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleExecutionError
                    when: ${ $failure.type == "EXECUTION_ERROR" }
                - case2:
                    then: HandleGenericError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT5S
      then: ProcessSuccess
```

### Timeouts for Long-Running Tasks

Set timeouts for scripts or containers:

```yaml
  - LongRunningTask:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "echo 'Starting long process...' && sleep 60 && echo 'Completed'"
      then: ProcessSuccess
      timeout:
        after: PT2M
  - HandleTimeout:
      set:
        status: "TIMEOUT"
        message: "The task exceeded the 2-minute timeout"
      then: Recovery
```

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

### Custom Working Directory

Set a custom working directory:

```yaml
  - RunInSpecificDirectory:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "pwd && ls -la"
      then: ProcessResult
```

### User and Group Settings

Run as a specific user or group:

```yaml
  - RunAsSpecificUser:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - "id && whoami"
      then: ProcessResult
```

## Real-World Example: Data Processing Pipeline

Here's a complete example that uses scripts and containers for a data processing pipeline:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: data-processing-pipeline
  version: 1.0.0
  title: Data Processing Pipeline
do:
  - ReceiveDataRequest:
      set:
        inputFile: ${ $workflow.input.inputFile }
        outputDirectory: ${ $workflow.input.outputDirectory }
        processingOptions: ${ $workflow.input.options }
        timestamp: ${ $workflow.startedAt }
      then: ValidateInputs
  - ValidateInputs:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - |
              #!/bin/bash
              if [[ ! -f "${INPUT_FILE}" ]]; then
                echo "{\"valid\": false, \"error\": \"Input file does not exist\"}" >&2
                exit 1
              fi

              if [[ ! -d "${OUTPUT_DIR}" ]]; then
                echo "{\"valid\": false, \"error\": \"Output directory does not exist\"}" >&2
                exit 1
              fi

              # Check file format
              MIME_TYPE=$(file --mime-type -b "${INPUT_FILE}")
              if [[ "${MIME_TYPE}" != "text/csv" && "${MIME_TYPE}" != "application/csv" ]]; then
                echo "{\"valid\": false, \"error\": \"Invalid file format: ${MIME_TYPE}, expected CSV\"}" >&2
                exit 1
              fi

              echo "{\"valid\": true, \"mimeType\": \"${MIME_TYPE}\", \"fileSize\": $(stat -c%s \"${INPUT_FILE}\")}"
        return: "all"
      then: CheckValidationResult
  - CheckValidationResult:
      switch:
        - case1:
            then: PreprocessData
            when: ${ .exitCode == 0 }
        - default:
            then: HandleValidationError
  - HandleValidationError:
      set:
        status: "ERROR"
        error: ${ .stderr | fromjson | .error }
        message: '${ "Validation failed: \(.error)" }'
      then: end
  - PreprocessData:
      run:
        shell:
          command: "python3"
          arguments:
            - "-c"
            - |
              import pandas as pd
              import os
              import json
              import sys

              try:
                  # Load the CSV file
                  input_file = os.environ['INPUT_FILE']
                  output_dir = os.environ['OUTPUT_DIR']
                  df = pd.read_csv(input_file)

                  # Basic preprocessing
                  # Remove rows with missing values
                  df_clean = df.dropna()

                  # Save the preprocessed file
                  preprocessed_file = os.path.join(output_dir, 'preprocessed.csv')
                  df_clean.to_csv(preprocessed_file, index=False)

                  # Output results
                  result = {
                      "originalRows": len(df),
                      "cleanedRows": len(df_clean),
                      "droppedRows": len(df) - len(df_clean),
                      "outputFile": preprocessed_file
                  }
                  print(json.dumps(result))
              except Exception as e:
                  print(json.dumps({"error": str(e)}), file=sys.stderr)
                  sys.exit(1)
        return: "all"
      then: CheckPreprocessingResult
  - CheckPreprocessingResult:
      switch:
        - case1:
            then: ProcessInContainer
            when: ${ .exitCode == 0 }
        - default:
            then: HandlePreprocessingError
  - HandlePreprocessingError:
      set:
        status: "ERROR"
        stage: "preprocessing"
        error: ${ .stderr | fromjson | .error }
        message: '${ "Preprocessing failed: \(.error)" }'
      then: end
  - ProcessInContainer:
      try:
        - RunAnalysisContainer:
            run:
              container:
                image: "data-analysis:1.0"
                command: "python"
                volumes:
                  ".outputDirectory": "/data"
                arguments:
                  - "/app/analyze.py"
                  - "--input"
                  - "/data/preprocessed.csv"
                  - "--output"
                  - "/data/results.json"
                  - "--options"
                  - "OPTIONS"
                environment:
                  OPTIONS: ${ .processingOptions | tostring }
            timeout:
              after: PT10M
      catch:
        as: failure
        then: HandleContainerError
        retry:
          limit:
            attempt:
              count: 2
      then: ValidateResults
  - HandleContainerError:
      set:
        status: "ERROR"
        stage: "container-processing"
        error: ${ $context.error }
        message: '${ "Container processing failed: \($context.error.message)" }'
      then: end
  - HandleProcessingTimeout:
      set:
        status: "TIMEOUT"
        stage: "container-processing"
        message: "Processing timed out after 10 minutes"
      then: end
  - ValidateResults:
      run:
        shell:
          command: "/bin/bash"
          arguments:
            - "-c"
            - |
              #!/bin/bash
              RESULTS_FILE="${OUTPUT_DIR}/results.json"

              if [[ ! -f "${RESULTS_FILE}" ]]; then
                echo "{\"valid\": false, \"error\": \"Results file not generated\"}" >&2
                exit 1
              fi

              # Check if valid JSON
              if ! jq empty "${RESULTS_FILE}" 2>/dev/null; then
                echo "{\"valid\": false, \"error\": \"Results file is not valid JSON\"}" >&2
                exit 1
              fi

              # Load and return the results
              RESULTS=$(cat "${RESULTS_FILE}")
              echo "{\"valid\": true, \"results\": ${RESULTS}}"
        return: "all"
      then: CheckResultsValidation
  - CheckResultsValidation:
      switch:
        - case1:
            then: GenerateReport
            when: ${ .exitCode == 0 }
        - default:
            then: HandleResultsValidationError
  - HandleResultsValidationError:
      set:
        status: "ERROR"
        stage: "results-validation"
        error: ${ .stderr | fromjson | .error }
        message: '${ "Results validation failed: \(.error)" }'
      then: end
  - GenerateReport:
      run:
        container:
          image: "report-generator:1.0"
          command: "python"
          volumes:
            ".outputDirectory": "/data"
          arguments:
            - "/app/generate_report.py"
            - "--data"
            - "/data/results.json"
            - "--output"
            - "/data/report.pdf"
      then: FinalizeProcessing
  - FinalizeProcessing:
      set:
        status: "COMPLETED"
        message: "Data processing completed successfully"
        preprocessing: ${ .PreprocessData.stdout | fromjson }
        results: ${ .ValidateResults.stdout | fromjson | .results }
        reportPath: ${ .outputDirectory + "/report.pdf" }
      then: end
```

This example demonstrates a complete data processing pipeline that:
1. Validates input files using a shell script
2. Preprocesses data with a Python script
3. Runs advanced analysis in a Docker container
4. Validates the results with another script
5. Generates a report using another container
6. Includes comprehensive error handling at each stage

## Best Practices for Run Tasks

1. **Validate Inputs**: Always validate inputs before passing them to scripts or containers
2. **Handle Exit Codes**: Check exit codes to determine success or failure
3. **Set Appropriate Timeouts**: Configure timeouts based on expected execution time (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
4. **Clean Up Resources**: Use appropriate cleanup policies for containers
5. **Use Structured Output**: Have scripts output structured JSON for easier parsing
6. **Implement Proper Error Handling**: Use try/catch blocks and retry policies for reliability
7. **Secure Secrets**: Never hardcode sensitive information; use environment variables referencing secrets
8. **Limit Resource Usage**: Set resource constraints for containers to prevent resource exhaustion
9. **Version Your Images**: Use specific version tags for container images, not `latest`
10. **Log Appropriately**: Include helpful logging in scripts for debugging
11. **Make Scripts Idempotent**: Ensure scripts can be safely retried

## Troubleshooting Common Issues

### Script Execution Failures

If scripts fail to execute:
- Check if the script has execute permissions
- Verify the script path is correct
- Ensure the script interpreter (bash, python, etc.) is available
- Check for syntax errors in the script
- Examine the environment variables passed to the script

### Container Issues

For container-related problems:
- Verify the container image exists and is accessible
- Check container logs for error messages
- Ensure volume mount paths are correct and accessible
- Verify container networking configuration
- Check for resource constraints (memory, CPU)

### Output Parsing Problems

If you have trouble with output:
- Ensure script output is valid JSON if using automatic parsing
- Check for unexpected stderr output
- Verify the script is outputting to the correct stream (stdout vs stderr)
- Consider using the `return: "all"` option to capture all outputs

## Next Steps

- Learn about [how to call gRPC functions](lemline-howto-grpc.md)
- Explore [how to emit and listen for events](lemline-howto-events.md)
- Understand [how to access secrets securely](lemline-howto-secrets.md)