# Set

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

<code-block lang="yaml" src="workflows/dsl-task-set/basic-usage.yaml"/>

In this example, the `initialData` task directly defines an object with `user` and `status` fields.
This object becomes the output of `initialData` and is subsequently passed as input to the `processData` task.

Here's another example using a runtime expression to combine input data:

<code-block lang="yaml" src="workflows/dsl-task-set/basic-usage-2.yaml" include-lines="6-12"/>

## Configuration Options

### `set` (Object, Required)

This mandatory property defines the structure and content of the task's output.

The value provided for `set` is evaluated as a template where [Runtime Expressions](dsl-runtime-expressions.md) (e.g.,
`${.fieldName}`, `${ $context.someValue }`) can be used.

The result of evaluating this structure becomes the **raw output** of the `Set` task.

<code-block lang="yaml" src="workflows/dsl-task-set/set-object-required.yaml" include-lines="8-17"/>

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Set` task's primary purpose is to generate its `rawOutput` based on the `set` configuration. Standard `output.as` and `export.as` then process this generated output.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>


## Set Task Examples

### Basic Variable Assignment

<code-block lang="yaml" src="workflows/dsl-task-set/basic-variable-assignment.yaml"/>

### Data Transformation

<code-block lang="yaml" src="workflows/dsl-task-set/data-transformation.yaml"/>

### Conditional Data Preparation

<code-block lang="yaml" src="workflows/reference/prepare-order-data.yaml"/>

### Aggregating Results

<code-block lang="yaml" src="workflows/reference/aggregate-order-data.yaml"/>

### Error Response Formatting

<code-block lang="yaml" src="workflows/reference/format-error-result.yaml"/>

## Working with JSON Paths

The Set task leverages JQ-style JSON path expressions for powerful data manipulation:

<code-block lang="yaml" src="workflows/dsl-task-set/working-with-json-paths.yaml"/>

Data tasks are essential for maintaining clean data flow throughout your workflows and preparing data in exactly the right format for each subsequent task. The Set task in particular offers significant flexibility in how you transform and manipulate data within your workflow's execution. 