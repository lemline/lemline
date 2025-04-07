# Set Task (`set`)

## Purpose

The `Set` task is used to dynamically create or modify data within the workflow's execution flow. Its primary function
is to define the exact output of the task by evaluating a specified structure, often
using [Runtime Expressions](dsl-runtime-expressions.md) to incorporate data from the task's input or the workflow
context.

It's commonly used for:

* Initializing data structures.
* Assigning values to variables.
* Transforming or restructuring data between tasks when the standard `input.from` or `output.as` transformations are
  insufficient or less clear.
* Explicitly defining the data to be passed forward.

## Basic Usage

Here's a simple example of using `Set` to create a new object:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: set-basic
  version: '1.0.0'
do:
  - initialData:
      set:
        user:
          name: "Alice"
          id: 123
        status: "active"
  - processData: # Input to this task is the output of initialData
      call: someFunction
      with:
        userData: "${ .user }"
        currentStatus: "${ .status }"

# Output of 'initialData' task: { "user": { "name": "Alice", "id": 123 }, "status": "active" }
```

In this example, the `initialData` task directly defines an object with `user` and `status` fields.
This object becomes the output of `initialData` and is subsequently passed as input to the `processData` task.

Here's another example using a runtime expression to combine input data:

```yaml
do:
  - combineNames: # Assume input is { "firstName": "Bob", "lastName": "Smith" }
      set:
        fullName: "${ .firstName + ' ' + .lastName }"
        originalInput: "${ . }" # Include original input if needed

# Output of 'combineNames': { "fullName": "Bob Smith", "originalInput": { "firstName": "Bob", "lastName": "Smith" } }
```

## Configuration Options

### `set` (Object, Required)

This mandatory property defines the structure and content of the task's output.

The value provided for `set` is evaluated as a template where [Runtime Expressions](dsl-runtime-expressions.md) (e.g.,
`${.fieldName}`, `${ $context.someValue }`) can be used.

The result of evaluating this structure becomes the **raw output** of the `Set` task.

```yaml
set:
  # Static values
  configValue: 100
  # Values from transformed input
  inputValue: "${ .inputField }"
  processedValue: "${ (.inputField * 2) + $context.offset }" # Combine input and context
  # Nested structures
  details:
    timestamp: "${ $runtime.now.iso8601 }" # Using runtime variable
    source: "workflowX"
```

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Set` task's primary purpose is to generate its `rawOutput` based on the `set` configuration. Standard `output.as` and `export.as` then process this generated output.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
