---
title: Timeouts
---

# Timeouts

## Purpose

Timeouts provide a crucial mechanism for limiting the maximum execution duration of an entire workflow or individual
tasks within it.

Setting timeouts helps prevent workflows or tasks from running indefinitely due to unexpected delays, external service
issues, or infinite loops, ensuring resource efficiency and predictable behavior.

## How Timeouts Work

When a configured timeout duration is reached for a workflow or a task:

1. The execution of the workflow or task is **abruptly interrupted**.
2. A standard **Timeout Error** is raised.
3. If this error is not caught by an enclosing `Try` task, the workflow or task transitions immediately to the `faulted`
   status phase, and the overall workflow execution halts.

## Configuration

Timeouts can be configured at two levels:

1. **Workflow Level**: Set using the top-level `timeout` property in the workflow document.
2. **Task Level**: Set using the `timeout` property within a specific task definition.

In both cases, the `timeout` property takes an object with the following structure:

* **`after`** (`duration`, Required): Specifies the duration after which the timeout occurs. The duration itself is
  defined using an object detailing days, hours, minutes, seconds, or milliseconds.

```yaml
# Workflow-level timeout
document:
  dsl: '1.0.0'
  namespace: examples
  name: workflow-with-timeout
  version: '1.0.0'
  timeout: # Applies to the entire workflow execution
    after:
      minutes: 30 # Workflow will fault if it runs longer than 30 minutes
do:
  - step1:
    # ...

---
# Task-level timeout
do:
  - callExternalService:
      call: http
      with:
        uri: https://api.externalservice.com/data
        method: get
      timeout: # Applies only to the callExternalService task
        after:
          seconds: 15 # Task will fault if it takes longer than 15 seconds
      then: processResult
  - processResult:
    # ...
```

## Timeout Error Details

When a timeout occurs, the specific error raised **must** conform to the following:

* **`type`**: `https://serverlessworkflow.io/spec/1.0.0/errors/timeout`
* **`status`**: `408` (Request Timeout) is recommended.
* **`instance`**: Should point to the JSON Pointer of the workflow or task that timed out (e.g., `/`,
  `/do/callExternalService`).

This standard error format allows for consistent error handling using `Try` tasks specifically targeting timeout
conditions. 