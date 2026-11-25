# Workflow Task Types

A workflow's `do` list contains named tasks. Each entry has exactly one name with a
task configuration beneath it. See [Supported Features](lemline-ref-supported-features.md)
for which configuration variants Lemline can execute.

| Task | Purpose |
| --- | --- |
| [`do`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#do) | Group tasks in a nested list |
| [`set`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#set) | Construct a value from literal data and expressions |
| [`switch`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#switch) | Choose a flow directive using conditions |
| [`for`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#for) | Iterate over a collection |
| [`fork`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#fork) | Run independent branches concurrently |
| [`wait`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#wait) | Pause an instance for a duration |
| [`try`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#try) | Catch errors, retry or run a fallback |
| [`raise`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#raise) | Raise a defined or inline workflow error |
| [`emit`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#emit) | Publish a CloudEvent |
| [`listen`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#listen) | Wait for matching CloudEvents |
| [`call`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#call) | Invoke a service or workflow function |
| [`run`](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#run) | Invoke a child workflow or other process |

## Connect tasks

A task can provide an `if` condition, configure data with `input`, `output`
and `export`, or change the default sequence with `then`.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

See [DSL Syntax Reference](lemline-ref-dsl-syntax.md) for workflow-level sections
and expression scopes.

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-301","total":250}'
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-302","total":1500}'
```

| Example | Expected result |
|---|---|
| ORD-301 | `{"orderId":"ORD-301","status":"accepted"}` |
| ORD-302 | `{"orderId":"ORD-302","status":"needs-review"}` |

