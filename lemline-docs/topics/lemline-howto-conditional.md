---
title: How to define conditional branches (switch)
---

# How to define conditional branches (switch)

This guide shows you how to implement conditional logic in your Lemline workflows using the `switch` task. You'll learn how to create dynamic branching based on workflow data, evaluate conditions using JQ expressions, and implement complex decision trees.

## Understanding the Switch Task

The `switch` task lets you create conditional branches in your workflow based on runtime data. It evaluates a list of conditions in order and directs the workflow to the first matching branch.

## Run the Examples

Each example below is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker
running. Run the commands from a second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`.
The worker logs `Workflow completed with output` with each result. The examples that call HTTP services use the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop). Start it now from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

Each branch target is a task that records the decision. All but the last one end with `then: exit`, which leaves
the task list instead of falling through to the next target.

## Basic Switch Structure

Here is the basic structure of a `switch` task. It chooses how to treat a warehouse temperature reading:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/basic-switch-structure.yaml"/>

Save it as `basic-switch-structure.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f basic-switch-structure.yaml
bin/lemline instance start examples basic-switch-structure --input '{"temperature":35}'
bin/lemline instance start examples basic-switch-structure --input '{"temperature":5}'
bin/lemline instance start examples basic-switch-structure --input '{"temperature":20}'
```

Expect `{"action":"cooling"}`, `{"action":"heating"}` and `{"action":"unchanged"}`. Each condition is a jq expression that evaluates to a boolean. The first condition that evaluates to `true` determines the next task. The last case has no `when`, so it always matches.

## Simple Binary Decision

Let's start with a simple yes/no decision. The workflow reads a product from the shop and compares its available units with the requested quantity, kept in `$context`:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/simple-binary-decision.yaml"/>

Save it as `simple-binary-decision.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f simple-binary-decision.yaml
bin/lemline instance start examples simple-binary-decision --input '{"productId":"SKU-1","quantity":2}'
bin/lemline instance start examples simple-binary-decision --input '{"productId":"SKU-2","quantity":20}'
```

The shop has 100 units of SKU-1 and 10 of every other product, so expect `{"decision":"process"}` then `{"decision":"backorder"}`.

## Multiple Conditions

You can define multiple conditions for more complex decision trees. This example assigns a loyalty tier from a customer's points:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/multiple-conditions.yaml"/>

Save it as `multiple-conditions.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f multiple-conditions.yaml
bin/lemline instance start examples multiple-conditions --input '{"points":90}'
bin/lemline instance start examples multiple-conditions --input '{"points":60}'
bin/lemline instance start examples multiple-conditions --input '{"points":30}'
bin/lemline instance start examples multiple-conditions --input '{"points":5}'
```

Expect the tiers `gold`, `silver`, `bronze` and `basic`.

## Using Complex Expressions

jq expressions in conditions can be complex logical statements. This example routes customer service requests:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/using-complex-expressions.yaml"/>

Save it as `using-complex-expressions.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f using-complex-expressions.yaml
bin/lemline instance start examples using-complex-expressions --input '{"type":"refund","amount":1500,"daysSincePurchase":3}'
bin/lemline instance start examples using-complex-expressions --input '{"type":"refund","amount":200,"daysSincePurchase":45}'
bin/lemline instance start examples using-complex-expressions --input '{"type":"refund","amount":200,"daysSincePurchase":10}'
bin/lemline instance start examples using-complex-expressions --input '{"type":"exchange","reason":"defective"}'
bin/lemline instance start examples using-complex-expressions --input '{"type":"exchange","reason":"wrong size"}'
bin/lemline instance start examples using-complex-expressions --input '{"type":"question"}'
```

Expect the routes `supervisor`, `return-policy`, `standard-refund`, `defective-exchange`, `standard-exchange` and `generic`. A large refund matches the first case even when it is also late, because only the first matching case is selected.

## Default Fallback

Always include a default condition as the last one in your switch task to ensure the workflow always has a path forward:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/default-fallback.yaml"/>

Save it as `default-fallback.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f default-fallback.yaml
bin/lemline instance start examples default-fallback --input '{"status":"approved"}'
bin/lemline instance start examples default-fallback --input '{"status":"pending"}'
bin/lemline instance start examples default-fallback --input '{"status":"rejected"}'
bin/lemline instance start examples default-fallback --input '{"status":"lost"}'
```

Expect `ship`, `wait`, `cancel` and, for the unexpected status, `investigate`. The last case has no `when`, so it always matches: there's always a path, even if none of the explicit conditions match.

## Working with Arrays and Objects

jq expressions can work with complex data structures. This example chooses a shipping method from item weights, the shipping preferences and the customer tier:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/working-with-arrays-and-objects.yaml"/>

Save it as `working-with-arrays-and-objects.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f working-with-arrays-and-objects.yaml
bin/lemline instance start examples working-with-arrays-and-objects --input '{"items":[{"weight":15},{"weight":10}]}'
bin/lemline instance start examples working-with-arrays-and-objects --input '{"items":[{"weight":2}],"shipping":{"express":true}}'
bin/lemline instance start examples working-with-arrays-and-objects --input '{"items":[{"weight":2}],"customer":{"tier":"premium"}}'
bin/lemline instance start examples working-with-arrays-and-objects --input '{"items":[{"weight":2}]}'
```

Expect `freight`, `express`, `priority` and `standard`. A missing field such as `.shipping.express` is `null`, so its condition is simply false.

## Combining Multiple Variables

You can reference multiple variables in a single condition:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/combining-multiple-variables.yaml"/>

Save it as `combining-multiple-variables.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f combining-multiple-variables.yaml
bin/lemline instance start examples combining-multiple-variables --input '{"customer":{"loyaltyYears":6},"order":{"total":150,"items":[]}}'
bin/lemline instance start examples combining-multiple-variables --input '{"customer":{"loyaltyYears":3},"order":{"total":50,"items":[]}}'
bin/lemline instance start examples combining-multiple-variables --input '{"customer":{"loyaltyYears":0},"order":{"total":50,"items":[{"category":"sale"}]}}'
bin/lemline instance start examples combining-multiple-variables --input '{"customer":{"loyaltyYears":0},"order":{"total":50,"items":[{"category":"garden"}]}}'
```

This example determines discounts based on customer loyalty, order total, and item categories. Expect `discountPercent` 20, 10, 5 and 0.

## Nested Decision Making

For complex decision making, you can use multiple switch tasks in sequence:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/nested-decision-making.yaml"/>

Save it as `nested-decision-making.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f nested-decision-making.yaml
bin/lemline instance start examples nested-decision-making --input '{"transaction":{"amount":20000},"customer":{"history":{"flags":["chargeback"]},"accountAgeDays":400}}'
bin/lemline instance start examples nested-decision-making --input '{"transaction":{"amount":20000},"customer":{"history":{"flags":[]},"accountAgeDays":400}}'
bin/lemline instance start examples nested-decision-making --input '{"transaction":{"amount":20000},"customer":{"history":{"flags":[]},"accountAgeDays":10}}'
bin/lemline instance start examples nested-decision-making --input '{"transaction":{"amount":500}}'
```

This example implements a two-level decision tree for risk evaluation. Expect the decisions `manual-review`, `approved`, `verification` and `standard`.

## Using Data Transformations before Switching

You can use a `set` task to prepare data for a switch decision:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/using-data-transformations-before-switching.yaml"/>

Save it as `using-data-transformations-before-switching.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f using-data-transformations-before-switching.yaml
bin/lemline instance start examples using-data-transformations-before-switching --input '{"orderDate":"2026-03-01T00:00:00Z","customer":{"joinDate":"2020-01-01T00:00:00Z"},"order":{"total":50,"items":[{"category":"restricted"}]}}'
bin/lemline instance start examples using-data-transformations-before-switching --input '{"orderDate":"2026-03-01T00:00:00Z","customer":{"joinDate":"2026-02-20T00:00:00Z"},"order":{"total":1500,"items":[]}}'
bin/lemline instance start examples using-data-transformations-before-switching --input '{"orderDate":"2026-03-01T00:00:00Z","customer":{"joinDate":"2020-01-01T00:00:00Z"},"order":{"total":1500,"items":[]}}'
bin/lemline instance start examples using-data-transformations-before-switching --input '{"orderDate":"2026-03-01T00:00:00Z","customer":{"joinDate":"2020-01-01T00:00:00Z"},"order":{"total":50,"items":[]}}'
```

This pattern improves readability by computing derived values before the switch. A customer is new when they joined less than 30 days before the order date. Expect the routes `compliance`, `verification`, `priority` and `standard`.

## Error Handling in Switch Tasks

If an expression in a condition fails, the switch task raises an expression error. Wrap the expression in jq's `try ... catch false` to treat a failure as `false`:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/error-handling-in-switch-tasks.yaml"/>

Save it as `error-handling-in-switch-tasks.yaml` and run one instance per branch:

```bash
bin/lemline definition post -f error-handling-in-switch-tasks.yaml
bin/lemline instance start examples error-handling-in-switch-tasks --input '{"paymentResult":{"status":"success"}}'
bin/lemline instance start examples error-handling-in-switch-tasks --input '{"paymentResult":{"status":"pending"}}'
bin/lemline instance start examples error-handling-in-switch-tasks --input '{"paymentResult":"timeout"}'
```

Expect `completed`, `waiting` and `payment-failed`. In the third input `paymentResult` is a string: reading `.status` from it fails, `try` turns the failure into `false`, and the default case handles the order.

## Combining Switch with Try/Catch

For robust conditional handling, combine switch with try/catch. This workflow reserves one unit, charges the order
in the shop, then routes on the charge status:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/combining-switch-with-try-catch.yaml"/>

The `switch` routes on the payment result. If the payment call fails, the `catch` retries the `try` block up to
three times, then runs `handleError`. The shop answers `approved` or `review`, so the default case is only a
safeguard. The `failures` input makes the shop answer HTTP 503 that many times before it accepts the charge.
Save it as `combining-switch-with-try-catch.yaml` and run four orders:

```bash
bin/lemline definition post -f combining-switch-with-try-catch.yaml
bin/lemline instance start examples combining-switch-with-try-catch --input '{"orderId":"ORD-501","token":"approved"}'
bin/lemline instance start examples combining-switch-with-try-catch --input '{"orderId":"ORD-502","token":"review"}'
bin/lemline instance start examples combining-switch-with-try-catch --input '{"orderId":"ORD-503","token":"approved","failures":2}'
bin/lemline instance start examples combining-switch-with-try-catch --input '{"orderId":"ORD-504","token":"declined"}'
```

Expect `completed` and `in-review` at once. ORD-503 completes after two retries. ORD-504 is declined with HTTP 402
on every attempt and ends with `{"orderStatus":"error"}` after the third retry. The worker picks up retries every
10 seconds, so allow about a minute for the last two.

## Real-World Example: Order Processing

Here's a complete example of an order processing workflow with multiple decision points. It reserves the stock,
asks both carriers for a quote, applies the customer's shipping choice, charges the payment and books the shipment:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/real-world-example-order-processing.yaml"/>

A task replaces the data it received with its own result. To keep the order available to later tasks, each task that produces data merges its result into the order kept in `$context.order`.
Save it as `real-world-example-order-processing.yaml` and run one order per outcome:

```bash
bin/lemline definition post -f real-world-example-order-processing.yaml
bin/lemline instance start examples shop-order-routing --input '{"orderId":"ORD-601","items":[{"id":"SKU-1","quantity":1}],"express":true,"paymentInfo":{"token":"approved"}}'
bin/lemline instance start examples shop-order-routing --input '{"orderId":"ORD-602","items":[{"id":"SKU-1","quantity":1}],"express":false,"paymentInfo":{"token":"approved"}}'
bin/lemline instance start examples shop-order-routing --input '{"orderId":"ORD-603","items":[{"id":"SKU-1","quantity":1}],"express":false,"paymentInfo":{"token":"review"}}'
bin/lemline instance start examples shop-order-routing --input '{"orderId":"ORD-604","items":[{"id":"SKU-1","quantity":1}],"express":false,"paymentInfo":{"token":"declined"}}'
bin/lemline instance start examples shop-order-routing --input '{"orderId":"ORD-605","items":[],"paymentInfo":{"token":"approved"}}'
bin/lemline instance start examples shop-order-routing --input '{"orderId":"ORD-606","items":[{"id":"SKU-1","quantity":500}],"paymentInfo":{"token":"approved"}}'
```

| Order | Expected `orderStatus` | What the shop shows |
|---|---|---|
| ORD-601 | `CONFIRMED`, message ending `ship via express.` | Reservation, approved charge, express shipment |
| ORD-602 | `CONFIRMED`, message ending `ship via economy.` | Reservation, approved charge, economy shipment |
| ORD-603 | `PENDING_REVIEW` | Reservation and a charge with status `review`, no shipment |
| ORD-604 | `PAYMENT_DECLINED` | Reservation and a declined charge, no shipment |
| ORD-605 | `REJECTED`, no items | Nothing |
| ORD-606 | `REJECTED`, out of stock | Nothing: the shop refuses the reservation with HTTP 409 |

This example does not release the stock of orders that are not shipped. See
[the compensation example](lemline-example-compensation.md) for that. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Best Practices for Switch Tasks

1. **Order conditions from specific to general**: Put more specific conditions first
2. **Always include a default case**: End the switch with a case that has no `when`
3. **Preprocess data when needed**: Use a `set` task before complex switching
4. **Keep conditions readable**: Break complex conditions into separate variables
5. **Avoid deep nesting**: Prefer multiple sequential switch tasks over deeply nested logic
6. **Use meaningful task names**: Name tasks according to the decision they represent
7. **Handle all edge cases**: Consider what happens if data is missing or unexpected
8. **Document complex logic**: Add comments explaining business rules

## Next Steps

- Learn about [executing tasks in parallel](lemline-howto-parallel.md)
- Explore [running loops in workflows](lemline-howto-loops.md)
- Understand [how to use try/catch for error handling](lemline-howto-try-catch.md)