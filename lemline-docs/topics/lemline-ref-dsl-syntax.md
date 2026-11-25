# DSL Syntax Reference

Lemline reads Open Workflow DSL definitions in YAML and JSON. See
[Supported Features](lemline-ref-supported-features.md) for the subset the runner executes.

## Workflow structure

A definition contains a `document` with its identity and a `do` list of named tasks.
The optional `input` and `output` sections describe data validation and transformation.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Data and expressions

Write a jq expression as `${ expression }`. Within a task, `.` is the current input,
`$workflow.input` is the original workflow input, and `$context` is the data shared by
tasks through `export.as`.

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

## Shared resources

Declare reusable authentication policies, errors, functions, retries, timeouts and
catalogs under `use`. Tasks can refer to these resources by their declared names.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

## Task and flow structure

A task list contains maps with one named task each. Constructs such as `for`, `fork`,
`switch` and `try` control flow inside the list. Use `then` to change the default
sequential flow.

<code-block lang="yaml" src="workflows/reference/map-order-items.yaml"/>

See [Task Types](lemline-ref-task-types.md) for individual constructs,
[Authentication](lemline-ref-auth.md) for credential placement, and
[How to Define a Workflow](lemline-howto-define-workflow.md) for the main authoring steps.

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
bin/lemline instance start examples transform-workflow-data --input '{"user":{"id":"C-100"},"payload":{"items":[{"id":"SKU-1"},{"id":"SKU-2"}]}}'
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":1}'
bin/lemline definition post -f map-order-items.yaml
bin/lemline instance start examples map-order-items --input '{"items":[{"id":"SKU-1","quantity":2,"price":25},{"id":"SKU-2","quantity":1,"price":12}]}'
```

| Example | Expected result |
|---|---|
| `validate-and-route-order` | `{"orderId":"ORD-301","status":"accepted"}` |
| `transform-workflow-data` | `{"customerId":"C-100","itemCount":2}` |
| `retry-and-fallback` | The stock, after one retry |
| `map-order-items` | The last line, `{"itemId":"SKU-2","index":1,"lineTotal":12}` |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

