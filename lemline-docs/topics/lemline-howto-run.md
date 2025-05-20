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
- name: ExecuteScript
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "echo 'Hello, World!'"]
  next: ProcessResult
```

### Script with Input Data

You can pass data to the script:

```yaml
- name: ProcessData
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "echo \"Processing data for: $CUSTOMER_ID\""]
    env:
      CUSTOMER_ID: ".customerId"
  next: NextTask
```

### Multiline Scripts

For more complex scripts, you can use multiline YAML:

```yaml
- name: AnalyzeData
  type: run
  script:
    command: "/bin/bash"
    args:
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
    env:
      PATTERN: ".searchPattern"
      INPUT_FILE: ".inputFilePath"
      OUTPUT_FILE: "/tmp/analysis_result.txt"
  next: ProcessResults
```

### Capturing Script Output

Script output (stdout) is captured and available in the task result:

```yaml
- name: GetSystemInfo
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "uname -a && df -h && free -m"]
  next: ProcessSystemInfo

- name: ProcessSystemInfo
  type: set
  data:
    systemInfoOutput: "."  # The full output from the previous task
    systemInfoLines: ". | split('\n')"
    message: "Captured system information: .systemInfoLines | length lines"
  next: NextTask
```

### Running Python Scripts

You can execute Python scripts:

```yaml
- name: RunPythonScript
  type: run
  script:
    command: "python3"
    args:
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
    env:
      INPUT_JSON: ".items | tostring"
  next: ProcessPythonResult
```

### Running External Script Files

You can also run external script files:

```yaml
- name: RunExternalScript
  type: run
  script:
    command: "/bin/bash"
    args: ["/path/to/scripts/process_data.sh"]
    env:
      INPUT_FILE: ".inputFile"
      OUTPUT_DIR: ".outputDirectory"
  next: HandleScriptResult
```

## Running Docker Containers

### Basic Container Execution

To run a Docker container:

```yaml
- name: RunContainer
  type: run
  container:
    image: "alpine:latest"
    command: ["echo", "Hello from container"]
  next: ProcessContainerResult
```

### Container with Environment Variables

Pass environment variables to the container:

```yaml
- name: ProcessInContainer
  type: run
  container:
    image: "python:3.9-slim"
    command: ["python", "-c", "import os; print(f'Processing data for: {os.environ[\"CUSTOMER_ID\"]}')"]
    env:
      CUSTOMER_ID: ".customerId"
  next: NextTask
```

### Container with Volume Mounts

Mount volumes for persistent storage:

```yaml
- name: AnalyzeDataInContainer
  type: run
  container:
    image: "data-processor:1.0"
    command: ["python", "/app/analyze.py", "--input", "/data/input.csv", "--output", "/data/output.json"]
    volumes:
      - hostPath: ".inputDirectory"
        containerPath: "/data"
  next: ProcessResults
```

### Container with Resource Limits

Set resource constraints:

```yaml
- name: ResourceIntensiveTask
  type: run
  container:
    image: "data-processor:1.0"
    command: ["python", "/app/process_big_data.py"]
    resources:
      cpus: 2
      memory: "4G"
  next: NextTask
```

### Container Networking

Configure container networking:

```yaml
- name: NetworkedContainer
  type: run
  container:
    image: "network-tool:latest"
    command: ["ping", "-c", "4", "service-b"]
    network: "my-service-network"
  next: ProcessResult
```

### Container Cleanup Policies

Control when containers are removed:

```yaml
- name: TemporaryContainer
  type: run
  container:
    image: "data-processor:1.0"
    command: ["python", "/app/process.py"]
    cleanup: "always"  # Remove container immediately after execution
  next: NextTask

- name: PersistentContainer
  type: run
  container:
    image: "debug-tools:latest"
    command: ["sleep", "3600"]
    cleanup: "eventually"  # Keep container for inspection, remove later
  next: InspectContainer
```

## Running Other Workflows

### Invoking a Subworkflow

You can run another workflow as a subflow:

```yaml
- name: InvokeSubworkflow
  type: run
  workflow:
    id: "data-validation"
    version: "1.0"
    input:
      customerId: ".customerId"
      orderData: ".orderData"
  next: ProcessSubworkflowResult
```

### Handling Subworkflow Results

The results of the subworkflow are available in the task output:

```yaml
- name: ProcessSubworkflowResult
  type: set
  data:
    validationPassed: ".result.valid"
    validationErrors: ".result.errors"
    message: ".validationPassed ? 'Validation passed' : 'Validation failed with ' + (.validationErrors | length | tostring) + ' errors'"
  next: NextTask
```

### Dynamic Subworkflow Selection

You can dynamically select which subworkflow to run:

```yaml
- name: DetermineWorkflow
  type: set
  data:
    workflowToRun: ".orderType == 'STANDARD' ? 'standard-order-process' : 'premium-order-process'"
    workflowVersion: "1.0"
  next: InvokeDynamicWorkflow

- name: InvokeDynamicWorkflow
  type: run
  workflow:
    id: ".workflowToRun"
    version: ".workflowVersion"
    input:
      orderId: ".orderId"
      customerData: ".customerData"
  next: ProcessWorkflowResult
```

## Capturing and Using Output

### Return Models

You can control what output is captured from the execution:

```yaml
- name: AnalyzeWithOptions
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "echo 'Standard output' && echo 'Error message' >&2 && exit 2"]
  return: "all"  # Capture stdout, stderr, and exit code
  next: ProcessCompleteOutput

- name: ProcessCompleteOutput
  type: set
  data:
    stdout: ".stdout"  # Standard output
    stderr: ".stderr"  # Error output
    exitCode: ".exitCode"  # Exit code
    success: ".exitCode == 0"
  next: NextTask
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
- name: GenerateJsonData
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "echo '{\"status\": \"success\", \"count\": 42, \"items\": [1, 2, 3]}'"]
  next: ProcessJsonOutput

- name: ProcessJsonOutput
  type: set
  data:
    status: ".status"
    count: ".count"
    items: ".items"
    firstItem: ".items[0]"
  next: NextTask
```

### Error Handling and Exit Codes

Handle errors using the exit code:

```yaml
- name: RunWithErrorHandling
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "if [[ $RANDOM -lt 16000 ]]; then echo 'Success'; else echo 'Failed' >&2; exit 1; fi"]
  return: "all"
  next: CheckResult

- name: CheckResult
  type: switch
  conditions:
    - condition: ".exitCode == 0"
      next: HandleSuccess
    - condition: true
      next: HandleError

- name: HandleSuccess
  type: set
  data:
    status: "SUCCESS"
    message: "Script executed successfully: .stdout"
  next: Continue

- name: HandleError
  type: set
  data:
    status: "ERROR"
    message: "Script failed with: .stderr"
    exitCode: ".exitCode"
  next: ErrorRecovery
```

## Advanced Run Task Features

### Using Try/Catch with Run Tasks

Wrap `run` tasks in `try` blocks for robust error handling:

```yaml
- name: AttemptExecution
  type: try
  retry:
    maxAttempts: 3
    interval: PT5S
  catch:
    - error: "EXECUTION_ERROR"
      next: HandleExecutionError
    - error: "*"
      next: HandleGenericError
  do:
    - name: ExecuteScript
      type: run
      script:
        command: "/bin/bash"
        args: ["-c", "./process_data.sh .inputFile"]
  next: ProcessSuccess
```

### Timeouts for Long-Running Tasks

Set timeouts for scripts or containers:

```yaml
- name: LongRunningTask
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "echo 'Starting long process...' && sleep 60 && echo 'Completed'"]
  timeout: PT2M  # 2-minute timeout
  timeoutNext: HandleTimeout
  next: ProcessSuccess

- name: HandleTimeout
  type: set
  data:
    status: "TIMEOUT"
    message: "The task exceeded the 2-minute timeout"
  next: Recovery
```

### Custom Working Directory

Set a custom working directory:

```yaml
- name: RunInSpecificDirectory
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "pwd && ls -la"]
    workdir: "/path/to/working/directory"
  next: ProcessResult
```

### User and Group Settings

Run as a specific user or group:

```yaml
- name: RunAsSpecificUser
  type: run
  script:
    command: "/bin/bash"
    args: ["-c", "id && whoami"]
    user: "app-user"
    group: "app-group"
  next: ProcessResult
```

## Real-World Example: Data Processing Pipeline

Here's a complete example that uses scripts and containers for a data processing pipeline:

```yaml
id: data-processing-pipeline
name: Data Processing Pipeline
version: '1.0'
specVersion: '1.0'
start: ReceiveDataRequest
tasks:
  - name: ReceiveDataRequest
    type: set
    data:
      inputFile: "$WORKFLOW.input.inputFile"
      outputDirectory: "$WORKFLOW.input.outputDirectory"
      processingOptions: "$WORKFLOW.input.options"
      timestamp: "$WORKFLOW.startTime"
    next: ValidateInputs
  
  - name: ValidateInputs
    type: run
    script:
      command: "/bin/bash"
      args:
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
      env:
        INPUT_FILE: ".inputFile"
        OUTPUT_DIR: ".outputDirectory"
    return: "all"
    next: CheckValidationResult
  
  - name: CheckValidationResult
    type: switch
    conditions:
      - condition: ".exitCode == 0"
        next: PreprocessData
      - condition: true
        next: HandleValidationError
  
  - name: HandleValidationError
    type: set
    data:
      status: "ERROR"
      error: ".stderr | fromjson | .error"
      message: "Validation failed: .error"
    end: true
  
  - name: PreprocessData
    type: run
    script:
      command: "python3"
      args:
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
      env:
        INPUT_FILE: ".inputFile"
        OUTPUT_DIR: ".outputDirectory"
    return: "all"
    next: CheckPreprocessingResult
  
  - name: CheckPreprocessingResult
    type: switch
    conditions:
      - condition: ".exitCode == 0"
        next: ProcessInContainer
      - condition: true
        next: HandlePreprocessingError
  
  - name: HandlePreprocessingError
    type: set
    data:
      status: "ERROR"
      stage: "preprocessing"
      error: ".stderr | fromjson | .error"
      message: "Preprocessing failed: .error"
    end: true
  
  - name: ProcessInContainer
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "*"
        next: HandleContainerError
    do:
      - name: RunAnalysisContainer
        type: run
        container:
          image: "data-analysis:1.0"
          command: ["python", "/app/analyze.py", "--input", "/data/preprocessed.csv", "--output", "/data/results.json", "--options", "OPTIONS"]
          volumes:
            - hostPath: ".outputDirectory"
              containerPath: "/data"
          env:
            OPTIONS: ".processingOptions | tostring"
        timeout: PT10M
        timeoutNext: HandleProcessingTimeout
    next: ValidateResults
  
  - name: HandleContainerError
    type: set
    data:
      status: "ERROR"
      stage: "container-processing"
      error: "$WORKFLOW.error"
      message: "Container processing failed: $WORKFLOW.error.message"
    end: true
  
  - name: HandleProcessingTimeout
    type: set
    data:
      status: "TIMEOUT"
      stage: "container-processing"
      message: "Processing timed out after 10 minutes"
    end: true
  
  - name: ValidateResults
    type: run
    script:
      command: "/bin/bash"
      args:
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
      env:
        OUTPUT_DIR: ".outputDirectory"
    return: "all"
    next: CheckResultsValidation
  
  - name: CheckResultsValidation
    type: switch
    conditions:
      - condition: ".exitCode == 0"
        next: GenerateReport
      - condition: true
        next: HandleResultsValidationError
  
  - name: HandleResultsValidationError
    type: set
    data:
      status: "ERROR"
      stage: "results-validation"
      error: ".stderr | fromjson | .error"
      message: "Results validation failed: .error"
    end: true
  
  - name: GenerateReport
    type: run
    container:
      image: "report-generator:1.0"
      command: ["python", "/app/generate_report.py", "--data", "/data/results.json", "--output", "/data/report.pdf"]
      volumes:
        - hostPath: ".outputDirectory"
          containerPath: "/data"
    next: FinalizeProcessing
  
  - name: FinalizeProcessing
    type: set
    data:
      status: "COMPLETED"
      message: "Data processing completed successfully"
      preprocessing: ".PreprocessData.stdout | fromjson"
      results: ".ValidateResults.stdout | fromjson | .results"
      reportPath: ".outputDirectory + '/report.pdf'"
    end: true
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
3. **Set Appropriate Timeouts**: Configure timeouts based on expected execution time
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