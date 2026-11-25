# Fan-out and fan-in

Fan-out distributes work. Fan-in brings the results together. The DSL has several
ways to model these patterns, depending on where the work happens.

## Run independent branches

A `fork` starts its branches concurrently. A non-competing fork joins their results
after all branches finish. Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) and supply `{"orderId":"QUOTE-1"}` to request the stock level and a shipping quote.

<code-block lang="yaml" src="workflows/reference/parallel-order-checks.yaml"/>

## Iterate over input data

A `for` task runs its body for each item of a collection. Bind the current value with
`each` and its index with `at`.

<code-block lang="yaml" src="workflows/reference/map-order-items.yaml"/>

## Collect results from independent services

If outside services publish events, use `listen.to.all` to require every event type.
Use correlation to scope matches to the current workflow instance.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

## Recover when a branch fails

Use `try` around the fork for a group-wide fallback. To recover inside a specific
branch, place the handler in that branch.

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

See [Parallel Tasks](lemline-howto-parallel.md) and [Event Correlation](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#correlation).

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The event examples need the tutorial publisher. Save it as `tutorial-publisher.yaml`:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f tutorial-publisher.yaml
bin/lemline definition post -f parallel-order-checks.yaml
bin/lemline instance start examples parallel-order-checks --input '{"orderId":"QUOTE-1"}'
bin/lemline definition post -f map-order-items.yaml
bin/lemline instance start examples map-order-items --input '{"items":[{"id":"SKU-1","quantity":2,"price":25},{"id":"SKU-2","quantity":1,"price":12}]}'
bin/lemline definition post -f listen-for-all-results.yaml
bin/lemline instance start examples listen-for-all-results
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.inventory.checked","data":{"stock":2}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.shipping.quoted","data":{"price":15}}'
bin/lemline definition post -f parallel-checks-with-fallback.yaml
bin/lemline instance start examples parallel-checks-with-fallback --input '{"orderId":"QUOTE-3","inventoryFailures":1}'
```

| Example | Expected result |
|---|---|
| `parallel-order-checks` | Both branch results in `checks` |
| `map-order-items` | The last iteration, `{"itemId":"SKU-2","index":1,"lineTotal":12}` |
| `listen-for-all-results` | `{"checks":[{"stock":2},{"price":15}]}` after both events |
| `parallel-checks-with-fallback` | `{"status":"needs-review"}` |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

