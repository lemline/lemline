---
title: How to pass data between tasks
---

# How to pass data between tasks

By default, the transformed output of one task becomes the input of the next task.
Use `input.from` when a task needs to select or reshape its input, and `output.as`
when a task needs to adapt its result for the following step.

## Shape input and output

The workflow below transforms its input, calculates a result and returns selected
fields in its output.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. Save the
definition as `transform-workflow-data.yaml`:

<code-block lang="yaml" src="workflows/reference/transform-workflow-data.yaml"/>

```bash
bin/lemline definition post -f transform-workflow-data.yaml
bin/lemline instance start examples transform-workflow-data --input '{"user":{"id":"C-100","name":"Ada Lovelace"},"payload":{"items":[{"id":"SKU-1"},{"id":"SKU-2"}]}}'
```

The workflow-level `input.from` keeps only `customer` and `items`, the task counts the items, and `output.as` keeps
two fields: expect `{"customerId":"C-100","itemCount":2}`.

## Share a value across tasks

Use `export.as` to update `$context`. Merge the new value with the existing context
when earlier context entries must remain accessible. Use `$workflow.input` to read
values supplied when the workflow started.

<code-block lang="yaml" src="workflows/reference/share-order-context.yaml"/>

Save it as `share-order-context.yaml`:

```bash
bin/lemline definition post -f share-order-context.yaml
bin/lemline instance start examples share-order-context --input '{"orderId":"ORD-803","customer":{"id":"C-100"}}'
```

The second task reads the customer from `$context` and the order ID from `$workflow.input`: expect
`{"orderId":"ORD-803","customerId":"C-100"}`.

See [Data Flow](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl.md#data-flow) for validation order and expression scopes.
