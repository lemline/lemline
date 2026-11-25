# jq expressions in workflows

Write a workflow expression as `${ ... }`. Lemline evaluates expressions using jq.
The current input is `.`. Use `$workflow.input` for the original input and `$context`
for values exported by earlier tasks.

## Transform data

Object construction, arrays, filters and pipes are useful for selecting and reshaping
data. This task counts items and computes the order total with `map`-style iteration
and `add`.

<code-block lang="yaml" src="workflows/reference/aggregate-order-data.yaml"/>

## Compare values and branch

Use jq comparisons inside `if` or `switch.when`. A switch case without a condition
provides a fallback.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Construct strings for integrations

Use jq string interpolation for dynamic URLs and `@uri` to escape values used in the
path.

<code-block lang="yaml" src="workflows/reference/dynamic-http-endpoint.yaml"/>

See [Runtime Expressions](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#runtime-expressions) for other variables and
[Time in Workflows](lemline-explain-time.md) for timestamp comparisons.

## Run the Examples

Every example on this page is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md), save each example under the file name used below, and run the commands from the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.

Start the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Keep the setup worker running and use a second terminal in the setup directory.

```bash
bin/lemline definition post -f aggregate-order-data.yaml
bin/lemline instance start examples aggregate-order-data --input '{"orderId":"ORD-801","items":[{"quantity":2,"price":12.5},{"quantity":1,"price":5}]}'
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-302","total":1500}'
bin/lemline definition post -f dynamic-http-endpoint.yaml
bin/lemline instance start examples dynamic-http-endpoint --input '{"productId":"SKU-2"}'
```

| Example | Expected result |
|---|---|
| `aggregate-order-data` | `{"orderId":"ORD-801","total":30,"itemCount":2}` |
| `validate-and-route-order` | `{"orderId":"ORD-302","status":"needs-review"}` |
| `dynamic-http-endpoint` | The watering can |

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

