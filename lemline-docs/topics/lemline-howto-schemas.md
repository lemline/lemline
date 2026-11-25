---
title: How to validate inputs and outputs with schemas
---

# How to validate inputs and outputs with schemas

This guide explains how to implement data validation in Lemline workflows using JSON Schema. You'll learn how to validate workflow inputs, task inputs and outputs, and exported data to ensure data quality and prevent errors.


## Run the Examples

Each example below is a complete workflow. Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker
running. Run the commands from a second terminal in the setup directory. With the JAR, substitute
`java -jar lemline.jar` for `bin/lemline`. A workflow-level schema is checked by `instance start`, so an invalid input
is refused on the command line; a task-level schema is checked when the task runs, so the instance faults with a
`validation` error. Some examples call the [tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop);
start it from the root of the Lemline source checkout:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

## Understanding Schema Validation in Lemline

Schema validation allows you to define the expected structure, types, and constraints for your data. Lemline uses JSON Schema 2020-12 for validation at various points in the workflow:

- **Workflow Input**: Validating data passed to the workflow
- **Task Input**: Validating data before task execution
- **Task Output**: Validating data produced by a task
- **Export Data**: Validating data promoted to the global context

By defining schemas, you can catch data issues early, before they cause problems downstream, making your workflows more robust and reliable.

## Basic Schema Structure

A JSON Schema defines the structure and constraints for JSON data:

<!-- workflow-example: data -->
```json
{
  "type": "object",
  "required": ["name", "email"],
  "properties": {
    "name": {
      "type": "string",
      "minLength": 1
    },
    "email": {
      "type": "string",
      "format": "email"
    },
    "age": {
      "type": "integer",
      "minimum": 18
    }
  }
}
```

This schema validates an object with:
- Required `name` and `email` properties
- `name` must be a non-empty string
- `email` must be a valid email address
- Optional `age` must be an integer of at least 18 if present

## Validating Workflow Input

### Adding an Input Schema

To validate data passed to your workflow, define an input schema:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/adding-an-input-schema.yaml"/>

Save it as `adding-an-input-schema.yaml`:

```bash
bin/lemline definition post -f adding-an-input-schema.yaml
bin/lemline instance start examples customer-onboarding --input '{"name":"Ada Lovelace","email":"ada@example.com","address":{"street":"12 Main St","city":"Springfield","zipCode":"12345"}}'
bin/lemline instance start examples customer-onboarding --input '{"name":"Ada Lovelace","email":"ada-at-example","address":{"street":"12 Main St","city":"Springfield","zipCode":"12345"}}'
```

The first instance completes with `{"accepted":"ada@example.com"}`. The second is refused: `ada-at-example` is not a valid email. Refused inputs never become instances: the CLI prints `Input validation failed against workflow schema` and the rule that failed.

### Providing Default Values

You can specify default values for optional fields:

<code-block lang="yaml" src="workflows/reference/default-workflow-input.yaml"/>

Save it as `default-workflow-input.yaml`:

```bash
bin/lemline definition post -f default-workflow-input.yaml
bin/lemline instance start examples default-workflow-input --input '{"orderId":"ORD-2"}'
bin/lemline instance start examples default-workflow-input --input '{"orderId":"ORD-3","currency":"EUR"}'
```

Expect `{"orderId":"ORD-2","priority":"NORMAL","currency":"USD"}`, then `{"orderId":"ORD-3","priority":"NORMAL","currency":"EUR"}`.
The workflow-level `input.from` expression merges defaults with the input before the first task runs. Values supplied by the caller take precedence. `$workflow.input` still holds the input as the caller supplied it.

### Accessing Validated Input

After validation, you can access the input data in your workflow:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/accessing-validated-input.yaml"/>

Save it as `accessing-validated-input.yaml`:

```bash
bin/lemline definition post -f accessing-validated-input.yaml
bin/lemline instance start examples accessing-validated-input --input '{"name":"Ada Lovelace","email":"ada@example.com","address":{"city":"Springfield"},"preferredContact":"email"}'
```

Expect the customer with `"phone":"Not provided"`, the fallback for the missing phone number.

## Task Input Validation

### Validating Task Input

To validate data before a task executes:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/validating-task-input.yaml"/>

Save it as `validating-task-input.yaml`:

```bash
bin/lemline definition post -f validating-task-input.yaml
bin/lemline instance start examples validating-task-input --input '{"amount":25,"currency":"EUR","paymentMethod":{"type":"CREDIT_CARD","token":"tok-1"}}'
bin/lemline instance start examples validating-task-input --input '{"amount":0,"currency":"EUR","paymentMethod":{"type":"CREDIT_CARD","token":"tok-2"}}'
```

The first instance records `Payment of 25 EUR requested` in the shop's audit list. The second faults with a `validation` error before the call: the amount must be greater than 0, and the shop receives nothing for `tok-2`.

If the input doesn't match the schema, a validation error is raised before the task executes.

### Input Transformation and Validation

You can combine transformation with validation:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/input-transformation-and-validation.yaml"/>

Save it as `input-transformation-and-validation.yaml`:

```bash
bin/lemline definition post -f input-transformation-and-validation.yaml
bin/lemline instance start examples input-transformation-and-validation --input '{"userId":"C-100","firstName":"Ada","lastName":"Lovelace","email":"Ada@Example.com","age":36}'
bin/lemline instance start examples input-transformation-and-validation --input '{"userId":"C-100","firstName":"","lastName":"Lovelace","email":"ada@example.com"}'
```

The schema checks the raw input; `input.from` then reshapes it. The first instance completes with `{"userProfile":{"id":"C-100","fullName":"Ada Lovelace","email":"ada@example.com","age":36},"isAdult":true}`. The second faults with a `validation` error: `firstName` is empty.

This example validates the input, then transforms it: `input.schema` is checked against the data the task receives, before `input.from` is applied.

## Task Output Validation

### Validating Task Output

To validate data produced by a task:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/validating-task-output.yaml"/>

Save it as `validating-task-output.yaml`:

```bash
bin/lemline definition post -f validating-task-output.yaml
bin/lemline instance start examples validating-task-output --input '{"orderId":"ORD-1","customerId":"C-100","customerName":"Ada Lovelace","items":[{"productId":"SKU-1","productName":"Garden hose","quantity":2,"price":25}]}'
bin/lemline instance start examples validating-task-output --input '{"orderId":"ORD-2","customerId":"C-100","customerName":"Ada Lovelace","items":[{"productId":"SKU-1","productName":"Garden hose","quantity":2,"price":-25}]}'
```

The first instance returns the summary with `subtotal` 50, `tax` 4 and `total` 54. In the second, the price is negative: the computed `subtotal` breaks `minimum: 0` and the task faults with a `validation` error.

If the output doesn't match the schema, a validation error is raised after the task executes.

### Output Transformation and Validation

Combine transformation with validation:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/output-transformation-and-validation.yaml"/>

Save it as `output-transformation-and-validation.yaml`:

```bash
bin/lemline definition post -f output-transformation-and-validation.yaml
bin/lemline instance start examples output-transformation-and-validation --input '{"category":"garden"}'
bin/lemline instance start examples output-transformation-and-validation --input '{"category":"toys"}'
```

Expect `{"status":"SUCCESS","data":{"items":["SKU-1","SKU-2","SKU-3"]}}`, then `{"status":"ERROR","error":{"message":"No products in this category"}}`. Both outputs satisfy the schema.

This transforms the API response into a standardized format and validates the structure.

## Validating Exported Data

### Export Schema Validation

To validate data being exported to the global context:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/export-schema-validation.yaml"/>

Save it as `export-schema-validation.yaml`:

```bash
bin/lemline definition post -f export-schema-validation.yaml
bin/lemline instance start examples export-schema-validation --input '{"customerId":"C-100"}'
bin/lemline instance start examples export-schema-validation --input '{"customerId":"C-300"}'
```

C-100 passes and the next task greets `Ada Lovelace`. The shop stores C-300 with the email `not-an-email`: the export breaks `format: email` and the task faults with a `validation` error.

This ensures that only valid customer data is exported to the global context.

## Advanced Schema Features

### Complex Property Dependencies

You can define dependencies between properties:

<code-block lang="yaml" src="workflows/reference/schema-property-dependencies.yaml"/>

Save it as `schema-property-dependencies.yaml`:

```bash
bin/lemline definition post -f schema-property-dependencies.yaml
bin/lemline instance start examples schema-property-dependencies --input '{"shippingMethod":"SAME_DAY","deliveryDate":"2026-12-01"}'
bin/lemline instance start examples schema-property-dependencies --input '{"shippingMethod":"EXPRESS"}'
bin/lemline instance start examples schema-property-dependencies --input '{"shippingMethod":"SAME_DAY"}'
```

The first two start. The third is refused: a same-day delivery needs a `deliveryDate`. Refused inputs never become instances: the CLI prints `Input validation failed against workflow schema` and the rule that failed.

This schema requires `deliveryDate` only when `shippingMethod` is "SAME_DAY".

### Conditional Schemas with if/then/else

You can apply different validations based on conditions:

<code-block lang="yaml" src="workflows/reference/schema-conditional-validation.yaml"/>

Save it as `schema-conditional-validation.yaml`:

```bash
bin/lemline definition post -f schema-conditional-validation.yaml
bin/lemline instance start examples schema-conditional-validation --input '{"paymentMethod":"CREDIT_CARD","cardNumber":"4111111111111111","expiryDate":"12/30"}'
bin/lemline instance start examples schema-conditional-validation --input '{"paymentMethod":"CREDIT_CARD"}'
bin/lemline instance start examples schema-conditional-validation --input '{"paymentMethod":"PAYPAL"}'
```

The first starts. The second is refused for the missing `cardNumber`, the third for the missing `paypalEmail`.

This schema requires different fields based on the payment method selected.

### Pattern Properties

You can validate properties that match a pattern:

<code-block lang="yaml" src="workflows/reference/schema-pattern-properties.yaml"/>

Save it as `schema-pattern-properties.yaml`:

```bash
bin/lemline definition post -f schema-pattern-properties.yaml
bin/lemline instance start examples schema-pattern-properties --input '{"name":"Garden hose","custom_color":"green"}'
bin/lemline instance start examples schema-pattern-properties --input '{"name":"Garden hose","color":"green"}'
```

The first starts. The second is refused: `color` does not start with `custom_` and additional properties are not allowed.

This schema allows any property that starts with "custom_" (and requires it to be a string), plus the "name" property, but no others.

### Advanced String Formats

JSON Schema supports several built-in formats for string validation:

<code-block lang="yaml" src="workflows/reference/schema-string-formats.yaml"/>

Save it as `schema-string-formats.yaml`:

```bash
bin/lemline definition post -f schema-string-formats.yaml
bin/lemline instance start examples schema-string-formats --input '{"email":"ada@example.com","website":"https://shop.example","date":"2026-12-01","dateTime":"2026-12-01T10:00:00Z","ipAddress":"127.0.0.1"}'
bin/lemline instance start examples schema-string-formats --input '{"date":"2026-13-45"}'
bin/lemline instance start examples schema-string-formats --input '{"email":"not-an-email"}'
```

Lemline checks formats: the first starts, the second is refused as not a valid RFC 3339 full-date, the third as not a valid RFC 5321 mailbox. `uri`, `time`, `ipv4` and `uuid` are checked the same way.

### Array Validation

For validating arrays and their contents:

<code-block lang="yaml" src="workflows/reference/schema-array-validation.yaml"/>

Save it as `schema-array-validation.yaml`:

```bash
bin/lemline definition post -f schema-array-validation.yaml
bin/lemline instance start examples schema-array-validation --input '{"tags":["garden","sale"],"coordinates":[45.7,4.8]}'
bin/lemline instance start examples schema-array-validation --input '{"tags":["sale","sale"]}'
bin/lemline instance start examples schema-array-validation --input '{"coordinates":[45.7]}'
```

The first starts. The second is refused because the tags are not unique, the third because a coordinate needs at least two numbers.

### Content Media Type

You can specify the expected media type for string content:

<code-block lang="yaml" src="workflows/reference/schema-media-type.yaml"/>

Save it as `schema-media-type.yaml`:

```bash
bin/lemline definition post -f schema-media-type.yaml
bin/lemline instance start examples schema-media-type --input '{"jsonData":"{\"sku\":\"SKU-1\"}"}'
bin/lemline instance start examples schema-media-type --input '{"jsonData":"not json"}'
```

Both start. `contentMediaType` is an annotation in JSON Schema 2020-12: Lemline does not check that the string holds JSON, HTML or CSV. Parse it in an expression, for example with `fromjson`, if the workflow needs it.

## Example: Order Processing

The input schema requires an order ID and a nonnegative total. The workflow
validates the input before choosing a processing path.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

Save it as `validate-and-route-order.yaml`:

```bash
bin/lemline definition post -f validate-and-route-order.yaml
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-301","total":250}'
bin/lemline instance start examples validate-and-route-order --input '{"orderId":"ORD-303","total":-5}'
```

The first completes with `{"orderId":"ORD-301","status":"accepted"}`. The second is refused: the total must be at least 0.

## Schema Reuse and References

For complex schemas that are used in multiple places, you can use references:

<code-block lang="yaml" src="workflows/reference/schema-with-address-definition.yaml"/>

Save it as `schema-with-address-definition.yaml`:

```bash
bin/lemline definition post -f schema-with-address-definition.yaml
bin/lemline instance start examples schema-with-address-definition --input '{"billing":{"street":"1 Main St","postalCode":"69001"},"shipping":{"street":"2 Side St","postalCode":"69002"}}'
bin/lemline instance start examples schema-with-address-definition --input '{"billing":{"street":"1 Main St"},"shipping":{"street":"2 Side St","postalCode":"69002"}}'
```

The first completes with `{"valid":true}`. The second is refused: the billing address has no `postalCode`.

Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
```

## Best Practices for Schema Validation

1. **Validate Early**: Add schema validation as early as possible in the workflow
2. **Be Specific**: Create detailed, specific schemas that precisely define your data requirements
3. **Default Values**: Provide defaults for optional fields when appropriate
4. **Schema Reuse**: Use schema references to avoid duplication
5. **Document Schemas**: Add comments to explain complex validation requirements
6. **Incremental Validation**: Validate at multiple points to catch issues early
7. **Performance Consideration**: Balance validation thoroughness with performance needs
8. **Version Schemas**: Include version information for evolving schemas
9. **Error Messages**: Configure clear, helpful validation error messages
10. **Test Edge Cases**: Test your schemas with both valid and invalid data

## Troubleshooting Schema Validation

### Common Validation Errors

The error names the rule that failed. These messages come from the examples above:

| Problem | Message |
|---|---|
| Missing required property | `required property 'postalCode' not found` |
| Wrong type | `integer found, string expected` |
| Too few array items | `must have at least 1 items but found 0` |
| Duplicate array items | `must have only unique items in the array` |
| Invalid format | `does not match the date pattern must be a valid RFC 3339 full-date` |
| Property not allowed | `property 'color' is not defined in the schema and the schema does not allow additional properties` |
| No matching `oneOf` branch | `must be valid to one and only one schema, but 0 are valid` |

For a workflow-level schema, `instance start` prints the message after `Input validation failed against workflow
schema`. For a task-level schema, the instance faults with a `validation` error that carries it.

### Debugging Validation Issues

When troubleshooting validation problems:

1. **Add Diagnostic Tasks**: Insert temporary tasks to inspect data before validation
2. **Simplify Schemas**: Temporarily simplify complex schemas to isolate issues
3. **Check Data Types**: Verify that data types match schema requirements
4. **Inspect Transformations**: Ensure transformations produce data matching the schema
5. **Validate Externally**: Use online JSON Schema validators to test schemas independently

## Next Steps

- Learn about [writing jq runtime expressions](lemline-howto-jq.md)
- Explore [input, output, and export](lemline-howto-io.md)
- Understand [how to pass data between tasks](lemline-howto-data-passing.md)
- Dive deeper into [data flow concepts](lemline-explain-jq.md)