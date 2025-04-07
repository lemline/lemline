# Wait

## Purpose

The `Wait` task is used to introduce a pause or delay into the workflow execution. It halts the workflow for a specified
duration before proceeding to the next task.

It's primarily used for:

* Implementing timed delays between tasks.
* Waiting for external processes or systems to complete, where a fixed delay is acceptable.
* Rate limiting or throttling workflow execution.
* Scheduling subsequent actions after a specific interval.

## Basic Usage

Here's a simple example of waiting for 5 seconds:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: wait-basic
  version: '1.0.0'
do:
  - startProcess:
      call: startLongRunningJob
      # ...
  - waitForCompletion:
      wait:
        duration: PT5S # Wait for 5 seconds
  - checkStatus:
      call: getJobStatus
      # Input to checkStatus is the output of startProcess,
      # as Wait just passes data through.
      # ...
```

In this example, after the `startProcess` task completes, the `waitForCompletion` task pauses the workflow for exactly 5
seconds before the `checkStatus` task is executed.

## Configuration Options

### `wait` (Object, Required)

This mandatory object defines the duration of the pause.

* **`duration` ** (String, Required):
  A [duration string](https://docs.oracle.com/javase/8/docs/api/java/time/Duration.html) that specifies
  the length of time to wait. It must be in the ISO-8601 duration format (e.g., `PT5S` for 5 seconds, `PT1H30M` for 1
  hour
  and 30 minutes).

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Wait` task typically acts as a pass-through for data; its `rawOutput` is identical to its `transformedInput` unless explicitly changed by `output.as`.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
