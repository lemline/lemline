# Do Task (`do`)

## Purpose

The `Do` task is a fundamental control flow construct in the Serverless Workflow DSL. Its primary purpose is to define a
sequence of one or more sub-tasks that are executed in the order they are declared.

The output of one task in the sequence becomes the input for the next, facilitating data flow through the
defined steps.

## Basic Usage

Here's a simple example of a `Do` task executing two sub-tasks sequentially:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: sequential-tasks
  version: '1.0.0'
do: # This is the main 'Do'
  - step1:
      do: # This is a `Do` task containing sub-tasks
        - taskA:
            set:
              result: "Value from Task A"
        - taskB:
            set:
              # Input to taskB is the output of taskA
              resultB: "${ .result + ' and Task B' }"

# Workflow output will be { "resultB": "Value from Task A and Task B" }
```

In this example, `taskA` executes first, setting the `result` field. Its output is then implicitly passed as input to
`taskB`, which uses that result to compute its own `resultB`.

## Configuration Options

The `Do` task itself has standard task configuration options like `input`, `output`, `if`, and `then`. Its main defining
characteristic is the `do` block containing the sequence of sub-tasks.

### `do` (List<String: Object>, Required)

This mandatory property contains a list of key-value pairs, where each key is a unique name for the sub-task and the
value defines the sub-task to be executed. The sub-tasks are executed sequentially in the order they appear in the list.

Each element in the list specifies a unique name for the sub-task and the task definition itself (e.g., `set`,
`call`, another `do`, etc.). The order in the list determines the execution sequence.

```yaml
do:
  - myDoTask: # Name of the parent Do task
      do: # The 'do' property containing the list of sub-tasks
        - firstSubTask:
            call: http
            with:
              uri: https://api.example.com/data
              method: get
        - secondSubTask:
            set:
              processedData: "${ .body }" # Access output from firstSubTask
```

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>


