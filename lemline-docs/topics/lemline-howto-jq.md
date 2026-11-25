---
title: How to write jq runtime expressions
---

# How to use jq in workflow expressions

Write expressions as `${ ... }`. Start with current task input `.`, move through
fields with jq, and use pipes to break a transformation into steps.

## Read and transform input

The following workflow builds an output from an array of order lines. The fallback
after `add` handles an empty array.

To run the examples, complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the
commands from a second terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for
`bin/lemline`. Save the definition as `aggregate-order-data.yaml`:

<code-block lang="yaml" src="workflows/reference/aggregate-order-data.yaml"/>

```bash
bin/lemline definition post -f aggregate-order-data.yaml
bin/lemline instance start examples aggregate-order-data --input '{"orderId":"ORD-801","items":[{"quantity":2,"price":12.5},{"quantity":1,"price":5}]}'
bin/lemline instance start examples aggregate-order-data --input '{"orderId":"ORD-802","items":[]}'
```

Expect `{"orderId":"ORD-801","total":30,"itemCount":2}`, then `{"orderId":"ORD-802","total":0,"itemCount":0}` for the empty order.

## Keep data between tasks

Use `$context` for earlier values exported by a task and `$workflow.input` for the
input supplied when the instance started.

<code-block lang="yaml" src="workflows/reference/share-order-context.yaml"/>

Save it as `share-order-context.yaml`:

```bash
bin/lemline definition post -f share-order-context.yaml
bin/lemline instance start examples share-order-context --input '{"orderId":"ORD-803","customer":{"id":"C-100"}}'
```

The second task no longer sees the customer in its input, but reads it from `$context`: expect
`{"orderId":"ORD-803","customerId":"C-100"}`.

## Check an expression with local data

Use the [jq command-line tool](https://jqlang.org) on a small sample with the shape you expect the task to receive.
Pass the expression inside `${ ... }` to jq as its filter.

```bash
printf '%s\n' '{"items":[{"quantity":2,"price":12.5}]}' |
  jq '[.items[] | .quantity * .price] | add // 0'
```

It prints `25`. Lemline evaluates expressions with a Java implementation of jq, so check functions in a workflow too:
some built-ins are missing, for example `todate`, which fails with an `expression` error
([issue 110](https://github.com/lemline/lemline/issues/110)). Use `todateiso8601` instead.

See [Runtime Expressions](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#runtime-expressions) for evaluation scopes and
[Debugging Workflows](lemline-howto-debug.md) for logging task input.
