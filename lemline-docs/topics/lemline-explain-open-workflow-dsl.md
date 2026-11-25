# Open Workflow DSL in Lemline

Open Workflow is a declarative language for composing named tasks in YAML or JSON.
A workflow defines metadata, data contracts, and the tasks that act on the data.
Lemline implements the subset described in [Supported Features](lemline-ref-supported-features.md).

## Definitions and execution

A definition has a stable identity under `document`. Each execution carries its own
input and state. A workflow instance runs the tasks under `do` in order unless a task
provides a different flow directive.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Data and expressions

A task receives input as `.`. Use `${ ... }` to evaluate a jq expression. The original
input is available as `$workflow.input`. Tasks can share values through `$context` by
exporting them with `export.as`.

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

## Resource declarations

The `use` section defines shared resources. For example, a named retry policy can be
referenced from any compatible catch block.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

See [How to Define a Workflow](lemline-howto-define-workflow.md) for authoring steps.

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-301","total":250}'
bin/lemline definition post -f transform-workflow-data.yaml
bin/lemline instance start examples transform-workflow-data --input '{"user":{"id":"C-100"},"payload":{"items":[{"id":"SKU-1"}]}}'
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
```

| Example | Expected result |
|---|---|
| `validate-and-route-order` | `{"orderId":"ORD-301","status":"accepted"}` |
| `transform-workflow-data` | `{"customerId":"C-100","itemCount":1}` |
| `retry-and-fallback` | `{"inventory":[],"stale":true}` after four failed calls; allow about a minute |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

