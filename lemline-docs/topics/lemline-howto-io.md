---
title: How to use input, output, and export
---

# How to use input, output, and export

This guide explains how to work with data flow in Lemline workflows, focusing on input validation, output transformation, and exporting data to the global context. You'll learn how to control data as it flows through your workflow.

## Understanding Data Flow in Lemline

Lemline's data flow model consists of three main components:

1. **Input**: Data coming into a task, including validation and transformation
2. **Output**: Data produced by a task, including transformation and validation
3. **Export**: Data promoted to the global workflow context for use across the workflow

Understanding how to use these components effectively is key to building robust workflows.

## Run the Examples

Each example below is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker
running. Run the commands from a second terminal in the setup directory. With the JAR, substitute
`java -jar lemline.jar` for `bin/lemline`. The worker logs `Workflow completed with output` with each result. The
examples that call HTTP services use the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop);
start it from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

## Working with Workflow Input

### Defining Workflow Input Schema

You can define an input schema at the workflow level to validate incoming data:

<code-block lang="yaml" src="workflows/lemline-howto-io/defining-workflow-input-schema.yaml"/>

Save it as `defining-workflow-input-schema.yaml`:

```bash
bin/lemline definition post -f defining-workflow-input-schema.yaml
bin/lemline instance start examples io-input-schema --input '{"orderId":"ORD-000123","customerId":"C-100","items":[{"productId":"SKU-1","quantity":2}]}'
bin/lemline instance start examples io-input-schema --input '{"orderId":"ORD-1","customerId":"C-100","items":[{"productId":"SKU-1","quantity":2}]}'
```

The first instance completes with `{"accepted":"ORD-000123"}`. The second is refused before any instance exists: the CLI prints `Input validation failed against workflow schema`, because `ORD-1` does not match the pattern.

This schema ensures that workflow input contains the required fields with the correct data types and formats.

### Accessing Workflow Input

You can access the original workflow input in any task using `$workflow.input`:

<code-block lang="yaml" src="workflows/lemline-howto-io/accessing-workflow-input.yaml"/>

Save it as `accessing-workflow-input.yaml`:

```bash
bin/lemline definition post -f accessing-workflow-input.yaml
bin/lemline instance start examples accessing-workflow-input --input '{"orderId":"ORD-1","customerId":"C-100","items":[{"productId":"SKU-1"},{"productId":"SKU-2"}]}'
```

The second task only receives `{"itemCount":2}` from the first, but reads the IDs from `$workflow.input`: expect `{"orderId":"ORD-1","customerId":"C-100","itemCount":2}`.

### Default Input Values

You can provide default values for workflow input fields:

<code-block lang="yaml" src="workflows/reference/default-workflow-input.yaml"/>

Save it as `default-workflow-input.yaml`:

```bash
bin/lemline definition post -f default-workflow-input.yaml
bin/lemline instance start examples default-workflow-input --input '{"orderId":"ORD-2"}'
bin/lemline instance start examples default-workflow-input --input '{"orderId":"ORD-3","currency":"EUR"}'
```

Expect `{"orderId":"ORD-2","priority":"NORMAL","currency":"USD"}`, then `{"orderId":"ORD-3","priority":"NORMAL","currency":"EUR"}`.

The workflow-level `input.from` expression merges defaults with the input before the first task runs. Values supplied by the caller take precedence. `$workflow.input` still holds the input as the caller supplied it.

## Task Input Processing

### Implicit Input

By default, each task receives the output of the previous task as its input:

<code-block lang="yaml" src="workflows/lemline-howto-io/implicit-input.yaml"/>

Save it as `implicit-input.yaml`:

```bash
bin/lemline definition post -f implicit-input.yaml
bin/lemline instance start examples implicit-input
```

Expect `{"originalValue":42,"newMessage":"Hello, World!"}`.

### Input Transformation

You can transform the input data before task execution:

<code-block lang="yaml" src="workflows/lemline-howto-io/input-transformation.yaml"/>

Save it as `input-transformation.yaml`:

```bash
bin/lemline definition post -f input-transformation.yaml
bin/lemline instance start examples input-transformation --input '{"userProfile":{"id":"C-100","name":"Ada Lovelace","email":"Ada@Example.com","phone":"+44 (20) 7946-0000"}}'
```

Expect `{"formattedUser":{"id":"C-100","name":"Ada Lovelace","email":"ada@example.com","phone":"442079460000"}}`.

The `input.from` field lets you reshape the incoming data before it's used in the task.

### Input Schema Validation

You can validate task input against a schema:

<code-block lang="yaml" src="workflows/lemline-howto-io/input-schema-validation.yaml"/>

Save it as `input-schema-validation.yaml`:

```bash
bin/lemline definition post -f input-schema-validation.yaml
bin/lemline instance start examples input-schema-validation --input '{"amount":25,"currency":"EUR","paymentMethod":{"type":"CREDIT"}}'
bin/lemline instance start examples input-schema-validation --input '{"amount":25,"currency":"JPY","paymentMethod":{"type":"CREDIT"}}'
```

The first instance completes with `{"validPayment":true,"formattedAmount":"$25"}`. The second faults at the task with a `validation` error, because `JPY` is not one of the allowed currencies.

If the input doesn't match the schema, a validation error is raised.

## Task Output Processing

### Basic Output

The output of a `set` task is the object defined under `set`:

<code-block lang="yaml" src="workflows/lemline-howto-io/basic-output.yaml"/>

Save it as `basic-output.yaml`:

```bash
bin/lemline definition post -f basic-output.yaml
bin/lemline instance start examples basic-output
```

Expect `{"status":"SUCCESS","result":42}`.

This task produces an output object with `status`, `result`, and `timestamp` fields.

### Output Transformation

You can transform the output data before passing it to the next task:

<code-block lang="yaml" src="workflows/lemline-howto-io/output-transformation.yaml"/>

Save it as `output-transformation.yaml`:

```bash
bin/lemline definition post -f output-transformation.yaml
bin/lemline instance start examples output-transformation --input '{"customerId":"C-100"}'
```

The shop returns the whole customer; `output.as` keeps three fields: `{"customer":"Ada Lovelace","tier":"gold","isGold":true}`.

The `output.as` field reshapes the task result before it's passed to the next task.

### Output Schema Validation

You can validate task output against a schema:

<code-block lang="yaml" src="workflows/lemline-howto-io/output-schema-validation.yaml"/>

Save it as `output-schema-validation.yaml`:

```bash
bin/lemline definition post -f output-schema-validation.yaml
bin/lemline instance start examples output-schema-validation --input '{"userId":"C-100","firstName":"Ada","lastName":"Lovelace","emailAddress":"ada@example.com","joinDate":"2020-01-01"}'
bin/lemline instance start examples output-schema-validation --input '{"userId":"C-100","firstName":"Ada","lastName":"Lovelace","joinDate":"2020-01-01"}'
```

The first instance returns the profile. In the second, `email` is `null`, which is not a string: the task faults with a `validation` error.

If the output doesn't match the schema, a validation error is raised.

## Exporting Data to Global Context

### Basic Export

The `export` field lets you promote data to the global workflow context:

<code-block lang="yaml" src="workflows/lemline-howto-io/basic-export.yaml"/>

Save it as `basic-export.yaml`:

```bash
bin/lemline definition post -f basic-export.yaml
bin/lemline instance start examples basic-export --input '{"customerId":"C-100"}'
```

The second task reads the exported customer: expect `{"greeting":"Hello Ada Lovelace"}`.

Later tasks can read the exported customer data from `$context.customer`.

### Exporting Selected Fields

You can export specific fields rather than the entire output:

<code-block lang="yaml" src="workflows/lemline-howto-io/exporting-selected-fields.yaml"/>

Save it as `exporting-selected-fields.yaml`:

```bash
bin/lemline definition post -f exporting-selected-fields.yaml
bin/lemline instance start examples exporting-selected-fields --input '{"productId":"SKU-2"}'
```

The last task shows the context: `{"context":{"productName":"Watering can","unitPrice":12}}`.

This exports only the selected fields to the global context.

### Export with Transformation

You can transform data during export:

<code-block lang="yaml" src="workflows/lemline-howto-io/export-with-transformation.yaml"/>

Save it as `export-with-transformation.yaml`:

```bash
bin/lemline definition post -f export-with-transformation.yaml
bin/lemline instance start examples export-with-transformation
```

The context holds the seven products, a `productMap` from ID to name, and `categories`: `["clothing","garden","home"]`.

This exports the original products array plus derived data structures.

### Export Schema Validation

You can validate exported data against a schema:

<code-block lang="yaml" src="workflows/lemline-howto-io/export-schema-validation.yaml"/>

Save it as `export-schema-validation.yaml`:

```bash
bin/lemline definition post -f export-schema-validation.yaml
bin/lemline instance start examples io-export-schema-validation --input '{"productId":"SKU-1"}'
bin/lemline instance start examples io-export-schema-validation --input '{"productId":"SKU-6"}'
```

SKU-1 is a garden product: the context holds it. SKU-6 is clothing, which the schema does not allow: the task faults with a `validation` error.

This ensures the exported data meets the specified schema requirements.

## Comprehensive Example: Order Processing

This workflow validates the input, routes the order based on its total, and
returns the order ID and processing status.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

Save it as `validate-and-route-order.yaml`:

```bash
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-301","total":250}'
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-302","total":1500}'
```

Expect `{"orderId":"ORD-301","status":"accepted"}` and `{"orderId":"ORD-302","status":"needs-review"}`.

## Advanced Patterns

### Chain of Responsibility

Chain multiple data transformations for complex processing:

<code-block lang="yaml" src="workflows/lemline-howto-io/chain-of-responsibility.yaml"/>

Save it as `chain-of-responsibility.yaml`:

```bash
bin/lemline definition post -f chain-of-responsibility.yaml
bin/lemline instance start examples chain-of-responsibility --input '{"rawData":"[{\"value\":1},{\"value\":2},{\"value\":3}]"}'
```

Expect `{"context":{"result":6}}`.

### Selective Processing with Condition-Based Export

Export different data based on conditions:

<code-block lang="yaml" src="workflows/lemline-howto-io/selective-processing-with-condition-based-export.yaml"/>

Save it as `selective-processing-with-condition-based-export.yaml`:

```bash
bin/lemline definition post -f selective-processing-with-condition-based-export.yaml
bin/lemline instance start examples selective-processing-with-condition-based-export --input '{"code":200,"data":{"orderId":"ORD-1"}}'
bin/lemline instance start examples selective-processing-with-condition-based-export --input '{"code":500,"error":"Payment service unavailable"}'
```

Expect `{"context":{"result":{"orderId":"ORD-1"},"isError":false}}`, then `{"context":{"result":"Payment service unavailable","isError":true}}`.

### Parameterized Tasks

Use exported data as task parameters:

<code-block lang="yaml" src="workflows/reference/share-order-context.yaml"/>

Save it as `share-order-context.yaml`:

```bash
bin/lemline definition post -f share-order-context.yaml
bin/lemline instance start examples share-order-context --input '{"orderId":"ORD-803","customer":{"id":"C-100"}}'
```

Expect `{"orderId":"ORD-803","customerId":"C-100"}`.

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Best Practices

1. **Validate Early**: Add schema validation as early as possible in the workflow
2. **Use Specific Schemas**: Create detailed, specific schemas for validation
3. **Transform Near Use**: Do data transformations close to where the data is used
4. **Export Judiciously**: Only export data needed by multiple tasks
5. **Document the Data Flow**: Add comments to explain complex transformations
6. **Use Meaningful Names**: Name exported data with clear, descriptive identifiers
7. **Prefer Task Chaining**: Pass data through task chains rather than global context when possible
8. **Validate Exported Data**: Add schemas to exported data for integrity
9. **Minimize Global State**: Avoid exporting too much to the global context
10. **Clean Structure**: Structure data consistently across the workflow

## Common Errors and Solutions

### Schema Validation Errors

**Problem**: Input data doesn't match the schema.
**Solution**: 
- Verify data structure matches schema requirements
- Check for missing required fields
- Ensure data types are correct
- Use default values for optional fields

### Missing Context Variables

**Problem**: Accessing a field in `$context` that has not been exported.
**Solution**:
- Ensure the variable is exported before use
- Check for typos in variable names
- Add a fallback such as `$context.variable // defaultValue`.

### Transformation Errors

**Problem**: jq transformation expressions fail.
**Solution**:
- Break complex transformations into simpler steps
- Use `try/catch` in expressions for error handling
- Add explicit type handling
- Debug with a separate diagnostic task

## Next Steps

- Learn about [writing jq runtime expressions](lemline-howto-jq.md)
- Explore [validating inputs and outputs with schemas](lemline-howto-schemas.md)
- Understand [passing data between tasks](lemline-howto-data-passing.md)
- Dive deeper into [runtime expressions and data flow](lemline-explain-jq.md)
