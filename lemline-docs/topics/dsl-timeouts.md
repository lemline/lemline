---
title: Timeouts
---

# Timeouts

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

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
  dsl: '1.0.3'
  namespace: examples
  name: workflow-with-timeout
  version: '1.0.0'
timeout:
  after: { minutes: 30 }
do:
  - callExternalService:
      call: http
      with:
        endpoint: https://api.externalservice.com/data
        method: get
      timeout:
        after: { seconds: 15 }
```

## Timeout Error Details

When a timeout occurs, the specific error raised **must** conform to the following:

* **`type`**: `https://open-workflow-specification.org/spec/1.0.0/errors/timeout`
* **`status`**: `408` (Request Timeout) is recommended.
* **`instance`**: Should point to the JSON Pointer of the workflow or task that timed out (e.g., `/`,
  `/do/callExternalService`).

This standard error format allows for consistent error handling using `Try` tasks specifically targeting timeout
conditions. 