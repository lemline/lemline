# Do

## Purpose

The `Do` task is a fundamental control flow construct in the Open Workflow DSL. Its primary purpose is to define a
sequence of one or more sub-tasks that are executed in the order they are declared.

The output of one task in the sequence becomes the input for the next, facilitating data flow through the
defined steps.

## Basic Usage

Here's a simple example of a `Do` task executing two sub-tasks sequentially:

<code-block lang="yaml" src="workflows/dsl-task-do/sequential-tasks.yaml"/>

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

This sequence reads a product from the shop and passes its ID to the next step.

<code-block lang="yaml" src="workflows/dsl-task-do/read-product-and-format.yaml"/>

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. In the setup directory, save each workflow under the file name used below. Use the worker terminal to check the output of each instance.

Use this command from the root of the source checkout to [start the tutorial shop](lemline-tutorial-setup.md#use-the-tutorial-shop).

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d --wait
```

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f sequential-tasks.yaml
bin/lemline instance start examples sequential-tasks
bin/lemline definition post -f read-product-and-format.yaml
bin/lemline instance start examples read-product-and-format
```

| Example | Expected output |
|---|---|
| `sequential-tasks` | `{"resultB":"Value from Task A and Task B"}` |
| `read-product-and-format` | `{"productId":"SKU-1"}` |

Check the worker terminal for the results. Then run this command from the source checkout to stop the shop.

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```
