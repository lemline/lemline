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

<code-block lang="yaml" src="workflows/dsl-task-set/set-basic.yaml"/>

In this example, the `initialData` task directly defines an object with `user` and `status` fields.
This object becomes the output of `initialData` and is subsequently passed as input to the `processData` task.

Here's another example using a runtime expression to combine input data:

<code-block lang="yaml" src="workflows/dsl-task-set/combine-names.yaml"/>

## Configuration Options

### `set` (Object, Required)

This mandatory property defines the structure and content of the task's output.

The value provided for `set` is evaluated as a template where [Runtime Expressions](dsl-runtime-expressions.md) (e.g.,
`${.fieldName}`, `${ $context.someValue }`) can be used.

The result of evaluating this structure becomes the **raw output** of the `Set` task.

<code-block lang="yaml" src="workflows/dsl-task-set/set-object-required.yaml"/>

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

Use jq expressions in `set` to build values from the task input.

<code-block lang="yaml" src="workflows/dsl-task-set/json-path-examples.yaml"/>

Data tasks are essential for maintaining clean data flow throughout your workflows and preparing data in exactly the right format for each subsequent task. The Set task in particular offers significant flexibility in how you transform and manipulate data within your workflow's execution.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. Save each workflow in the setup directory under the file name used below.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f set-basic.yaml
bin/lemline instance start examples set-basic
bin/lemline definition post -f combine-names.yaml
bin/lemline instance start examples combine-names --input '{"firstName":"Ada","lastName":"Lovelace"}'
bin/lemline definition post -f set-object-required.yaml
bin/lemline instance start examples set-object-required --input '{"inputField":4}'
bin/lemline definition post -f basic-variable-assignment.yaml
bin/lemline instance start examples basic-variable-assignment --input '{"orderId":"O-100","customer":{"firstName":"Ada","lastName":"Lovelace"}}'
bin/lemline definition post -f data-transformation.yaml
bin/lemline instance start examples data-transformation --input '{"customerId":"C-100"}'
bin/lemline definition post -f prepare-order-data.yaml
bin/lemline instance start examples prepare-order-data --input '{"customerId":"C-100"}'
bin/lemline definition post -f aggregate-order-data.yaml
bin/lemline instance start examples aggregate-order-data --input '{"orderId":"O-100","items":[{"quantity":2,"price":11},{"quantity":1,"price":5}]}'
bin/lemline definition post -f format-error-result.yaml
bin/lemline instance start examples format-error-result --input '{"orderId":"O-101","items":[{"id":"SKU-9","quantity":1}]}'
bin/lemline definition post -f json-path-examples.yaml
bin/lemline instance start examples json-path-examples --input '{"order":{"customer":{"contactInfo":{"email":"ada@example.com"}}},"items":["SKU-1","SKU-2"],"products":[{"name":"Desk","price":150},{"name":"Lamp","price":60}],"list1":[1,2],"list2":[3],"approved":true,"name":"Ada","id":42}'
```

Check the individual results in the worker terminal.

| Example | Expected result |
|---|---|
| `set-basic` | `{"userId":123,"active":true}` |
| `combine-names` | `{"fullName":"Ada Lovelace","originalInput":{"firstName":"Ada","lastName":"Lovelace"}}` |
| `set-object-required` | `{"configValue":100,"inputValue":4,"processedValue":11,"details":{"source":"workflowX"}}` |
| `basic-variable-assignment` | `{"orderData":{"id":"O-100","status":"PENDING","customerName":"Ada Lovelace","priority":"normal"}}` |
| `data-transformation` | `profile.displayName` is `Ada Lovelace`, `profile.canContact` is `true`, and `profile.email` contains her address. |
| `prepare-order-data` | `{"customer":{"id":"C-100","guest":false}}` |
| `aggregate-order-data` | `{"orderId":"O-100","total":27,"itemCount":2}` |
| `format-error-result` | The instance completes with `success: false`, HTTP status `400`, and a communication error type. |
| `json-path-examples` | `{"customerEmail":"ada@example.com","firstItem":"SKU-1","itemCount":2,"expensiveItems":[{"name":"Desk","price":150}],"productNames":["Desk","Lamp"],"combinedList":[1,2,3],"status":"APPROVED","upperCaseName":"ADA","formattedId":"0000000042"}` |

When the instances finish, use the command below in the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
