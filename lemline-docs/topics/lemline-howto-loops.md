---
title: How to run loops (for, while)
---

# How to run loops (for, while)

Use a `for` task to iterate over an array. The `in` expression selects the collection,
`each` binds an item and `at` binds its index. The body is a nested `do` list.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. The worker logs
`Workflow completed with output` with each result. Save each definition under the file name used in its
`definition post` command.

<code-block lang="yaml" src="workflows/reference/map-order-items.yaml"/>

```bash
bin/lemline definition post -f map-order-items.yaml
bin/lemline instance start examples map-order-items --input '{"items":[{"id":"SKU-1","quantity":2,"price":25},{"id":"SKU-2","quantity":1,"price":12}]}'
```

The body runs once per item, but the `for` task returns only the last iteration's result:
`{"itemId":"SKU-2","index":1,"lineTotal":12}`. To keep every line, collect them as shown next.

## Collect a result across iterations

Lemline passes one iteration's result into the next iteration. Initialize an
accumulator, then update it in the body. The last iteration's result becomes the
loop result. Here the body adds a line total for each input item.

<code-block lang="yaml" src="workflows/reference/collect-order-lines.yaml"/>

The workflow's `input.from` starts the loop with an empty `lines` array. Each iteration receives the previous one's
result and appends a line.

```bash
bin/lemline definition post -f collect-order-lines.yaml
bin/lemline instance start examples collect-order-lines --input '{"items":[{"id":"SKU-1","quantity":2,"price":25},{"id":"SKU-2","quantity":1,"price":12}]}'
```

Expect `{"lines":[{"id":"SKU-1","amount":50},{"id":"SKU-2","amount":12}]}`.

## Stop a loop early

Add a `while` condition to the same task when the loop should stop before the
collection is exhausted. See the [For Task](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#for) for the condition and
its scope. Use a [fork](lemline-howto-parallel.md) for independent concurrent branches.
