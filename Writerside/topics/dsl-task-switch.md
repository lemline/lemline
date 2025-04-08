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

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: switch-basic
  version: '1.0.0'
do:
  - decideNextStep:
      # Assume input is { "status": "Approved" } or { "status": "Rejected" } etc.
      switch:
        - caseApproved:
            when: "${ .status == \"Approved\" }"
            then: processApproved # Go to the 'processApproved' task
        - caseRejected:
            when: "${ .status == \"Rejected\" }"
            then: processRejected # Go to the 'processRejected' task
        - caseDefault: # Default case (no 'when')
            then: handleOtherStatus # Go to 'handleOtherStatus' if neither matched
  - processApproved:
      # ... task definition ...
      then: continue # Or specify next step
  - processRejected:
      # ... task definition ...
      then: continue
  - handleOtherStatus:
      # ... task definition ...
      then: continue
```

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

```yaml
do:
  - switch:
      - checkHighPriority:
          when: "${ .priority > 5 }"
          then: handleHighPriority
      - checkMediumPriority:
          when: "${ .priority > 2 }" # Only checked if priority <= 5
          then: handleMediumPriority
      - defaultCase: # No 'when', acts as default
          then: handleLowPriority
  - handleHighPriority:
      # ... task definition ...
      then: exit
  - handleMediumPriority:
      # ... task definition ...
      then: exit
  - handleLowPriority:
      # ... task definition ...
      then: exit
```

**Important**: Only the *first* `when` condition that evaluates to `true` is selected. Subsequent cases are ignored. If
a default case (no `when`) exists, it should typically be placed last.
Targeted tasks typically have a `then: exit` to prevent further execution.

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `rawOutput` of the `Switch` task (feeding into its `output.as`/`export.as`) is its own `transformedInput`. The `Switch` itself doesn't modify the data; it only directs the flow.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: The `Switch` task has unique flow control. Instead of using its own `then` property, flow continues based on the `then` property of the *matched* `case` within the `switch` block. If no case matches, flow follows the `Switch` task's own `then` property.

