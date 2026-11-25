# Switch

## Purpose

The `Switch` task provides conditional branching based on evaluating a series of conditions. It allows the workflow to
dynamically select one execution path from multiple alternatives.

It evaluates conditions (defined by `when` expressions) sequentially. The `then` directive associated with the *first*
condition that evaluates to `true` is executed, determining the next step in the workflow. If no conditions evaluate to
`true`, a default path (the case without a `when` or the `Switch` task's own `then` directive) is taken.

It's primarily used for:

* Implementing decision logic (if-elseif-else patterns).
* Routing workflow execution based on input data or context state.
* Selecting different processing paths for different types of data.

## Basic Usage

Here's an example of routing based on an input status field:

<code-block lang="yaml" src="workflows/dsl-task-switch/switch-basic.yaml"/>

In this example, the `decideNextStep` task inspects the `.status` field of its input. If it's "Approved", execution
jumps to `processApproved`. If it's "Rejected", it jumps to `processRejected`. Otherwise, the default case triggers,
jumping to `handleOtherStatus`.

## Configuration Options

### `switch` (List<String, Object>, Required)

This mandatory property contains a list of cases, each representing a potential branch or case.

The runtime evaluates the items in the order they appear in the list.

Each case contains:

* **A unique name** for the case (e.g., `caseApproved`).
* **An object** containing:
    * **`when`** (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md). If present, this expression is
      evaluated against the *transformed input* of the `Switch` task. If it evaluates to `true`, this case is selected,
      and its `then` directive is followed. If omitted, this case acts as the default branch (executed only if no
      preceding `when` condition was met).
    * **`then`** (String, Required): Defines the next step if this case is selected. It follows the
      standard [Flow Control](dsl-flow-control.md) rules (`continue`, `exit`, `end`, or a task name).

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

**Important**: Only the *first* `when` condition that evaluates to `true` is selected. Subsequent cases are ignored. If
a default case (no `when`) exists, it should typically be placed last.
Targeted tasks typically have a `then: exit` to prevent further execution.

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `rawOutput` of the `Switch` task (feeding into its `output.as`/`export.as`) is its own `transformedInput`. The `Switch` itself doesn't modify the data; it only directs the flow.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: The `Switch` task has unique flow control. Instead of using its own `then` property, flow continues based on the `then` property of the *matched* `case` within the `switch` block. If no case matches, flow follows the `Switch` task's own `then` property.

## Run the Examples

Complete the [SQLite setup](lemline-tutorial-setup.md) and leave the worker running. In the setup directory, save each workflow under the file name used below. Use the worker terminal to check the output of each instance.

Run these registration and start commands from the setup directory.

```bash
bin/lemline definition post -f switch-basic.yaml
bin/lemline instance start examples switch-basic --input '{"status":"Approved"}'
bin/lemline instance start examples switch-basic --input '{"status":"Rejected"}'
bin/lemline instance start examples switch-basic --input '{"status":"Pending"}'
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"O-100","total":1250}'
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"O-101","total":75}'
```

| Example | Expected output |
|---|---|
| `switch-basic` with `{"status":"Approved"}` | `{"route":"approved"}` |
| `switch-basic` with `{"status":"Rejected"}` | `{"route":"rejected"}` |
| `switch-basic` with `{"status":"Pending"}` | `{"route":"manual-review"}` |
| `validate-and-route-order` with `{"orderId":"O-100","total":1250}` | `{"orderId":"O-100","status":"needs-review"}` |
| `validate-and-route-order` with `{"orderId":"O-101","total":75}` | `{"orderId":"O-101","status":"accepted"}` |
