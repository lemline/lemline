---
title: How to define a workflow
---

# How to define a workflow

Start with a YAML file containing a `document` section and a `do` list.

To run the examples, complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the
commands from a second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for
`bin/lemline`. Save each definition under the file name used in its `definition post` command.

Save this first definition as `hello.yaml`:

<code-block lang="yaml" src="workflows/lemline-getting-started/creating-your-first-workflow.yaml"/>

```bash
bin/lemline definition post -f hello.yaml
bin/lemline instance start examples shop-receipt
```

The worker logs `Workflow completed with output` with `{"message":"Order received"}`.

## Identify the workflow

The `document` section contains four required fields:

| Field | Purpose |
| --- | --- |
| `dsl` | Open Workflow DSL version, such as `1.0.0` |
| `namespace` | Namespace the runner serves |
| `name` | Workflow name within that namespace |
| `version` | Semantic version of this workflow definition |

The combination of namespace, name and version identifies a definition. Use the optional `title`
and `summary` fields for human-readable descriptions.

## Define and connect tasks

Write one named task per entry in `do`. Tasks run in list order by default. Add `then` to
jump to a named task or end execution. A `switch` lists named cases with conditions and
flow directives. A final case without `when` provides a fallback.

## Validate and transform data

Use `input.schema.document` to declare a JSON Schema and `output.as` to shape the final
result. Expressions delimited by `${ ... }` use jq. The task input is `.` and the original
workflow input is available as `$workflow.input`.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

Save it as `validate-and-route-order.yaml`, register it and start one small order, one large order and one order without a total:

```bash
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-301","total":250}'
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-302","total":1500}'
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-303"}'
```

The first instance completes with `{"orderId":"ORD-301","status":"accepted"}` and the second with
`{"orderId":"ORD-302","status":"needs-review"}`. The third is refused before any instance exists:
the CLI prints `Input validation failed against workflow schema` and `Missing required field(s): 'total'`.

## Reuse workflow resources

Use the `use` section for named functions, errors, authentication policies and retry
policies. The following workflow declares a reusable retry policy and selects it in
`catch.retry`.

<code-block lang="yaml" src="workflows/reference/retry-and-fallback.yaml"/>

The request goes to the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop). Its
`fail` and `failTimes` query parameters make the shop answer HTTP 503 a chosen number of times, and `key`
counts those failures per instance. Start the shop from the root of the Lemline source checkout, then save the
definition as `retry-and-fallback.yaml` and run it with no failure, one failure and five failures:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

```bash
bin/lemline definition post -f retry-and-fallback.yaml
bin/lemline instance start examples retry-and-fallback --input '{"failures":0}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":1}'
bin/lemline instance start examples retry-and-fallback --input '{"failures":5}'
```

The first two instances return the stock, `{"sku":"SKU-1","available":100,"reserved":0}`; the second one after a
retry about 2 seconds later. The third fails four times (the call and three retries), then returns
`{"inventory":[],"stale":true}` after the `catch` block runs. The worker picks up delayed retries every 10 seconds,
so allow about a minute. The shop page lists every attempt under **Requests**. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

See [Publish and Run a Workflow](lemline-howto-publish-run.md) to register and start
your definition, or [DSL Syntax Reference](lemline-ref-dsl-syntax.md) for the main
workflow sections and task types.
