---
title: How to validate inputs and outputs with schemas
---

# How to validate inputs and outputs with schemas

This guide explains how to implement data validation in Lemline workflows using JSON Schema. You'll learn how to validate workflow inputs, task inputs and outputs, and exported data to ensure data quality and prevent errors.

## Understanding Schema Validation in Lemline

Schema validation allows you to define the expected structure, types, and constraints for your data. Lemline uses JSON Schema (draft-07) for validation at various points in the workflow:

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

### Providing Default Values

You can specify default values for optional fields:

<code-block lang="yaml" src="workflows/reference/default-workflow-input.yaml"/>

The workflow-level `input.from` expression merges defaults with the input before the first task runs. Values supplied by the caller take precedence. `$workflow.input` still holds the input as the caller supplied it.

### Accessing Validated Input

After validation, you can access the input data in your workflow:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/accessing-validated-input.yaml" include-lines="7-15"/>

## Task Input Validation

### Validating Task Input

To validate data before a task executes:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/validating-task-input.yaml" include-lines="7-45"/>

If the input doesn't match the schema, a validation error is raised before the task executes.

### Input Transformation and Validation

You can combine transformation with validation:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/input-transformation-and-validation.yaml" include-lines="7-41"/>

This example validates the input, then transforms it: `input.schema` is checked against the data the task receives, before `input.from` is applied.

## Task Output Validation

### Validating Task Output

To validate data produced by a task:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/validating-task-output.yaml" include-lines="7-62"/>

If the output doesn't match the schema, a validation error is raised after the task executes.

### Output Transformation and Validation

Combine transformation with validation:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/output-transformation-and-validation.yaml" include-lines="7-47"/>

This transforms the API response into a standardized format and validates the structure.

## Validating Exported Data

### Export Schema Validation

To validate data being exported to the global context:

<code-block lang="yaml" src="workflows/lemline-howto-schemas/export-schema-validation.yaml" include-lines="7-41"/>

This ensures that only valid customer data is exported to the global context.

## Advanced Schema Features

### Complex Property Dependencies

You can define dependencies between properties:

<code-block lang="yaml" src="workflows/reference/schema-property-dependencies.yaml" include-lines="6-35"/>

This schema requires `deliveryDate` only when `shippingMethod` is "SAME_DAY".

### Conditional Schemas with if/then/else

You can apply different validations based on conditions:

<code-block lang="yaml" src="workflows/reference/schema-conditional-validation.yaml" include-lines="6-54"/>

This schema requires different fields based on the payment method selected.

### Pattern Properties

You can validate properties that match a pattern:

<code-block lang="yaml" src="workflows/reference/schema-pattern-properties.yaml" include-lines="6-16"/>

This schema allows any property that starts with "custom_" (and requires it to be a string), plus the "name" property, but no others.

### Advanced String Formats

JSON Schema supports several built-in formats for string validation:

<code-block lang="yaml" src="workflows/reference/schema-string-formats.yaml" include-lines="6-31"/>

### Array Validation

For validating arrays and their contents:

<code-block lang="yaml" src="workflows/reference/schema-array-validation.yaml" include-lines="6-30"/>

### Content Media Type

You can specify the expected media type for string content:

<code-block lang="yaml" src="workflows/reference/schema-media-type.yaml" include-lines="6-19"/>

## Example: Order Processing

The input schema requires an order ID and a nonnegative total. The workflow
validates the input before choosing a processing path.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Schema Reuse and References

For complex schemas that are used in multiple places, you can use references:

<code-block lang="yaml" src="workflows/reference/schema-with-address-definition.yaml"/>

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

1. **Missing Required Properties**:
   ```
   Validation error: Required property 'customerId' is missing
   ```
   Solution: Ensure all required properties are present in your data.

2. **Type Mismatches**:
   ```
   Validation error: Property 'quantity' must be of type integer
   ```
   Solution: Check data types and use transformation to convert if needed.

3. **Pattern Matching Failures**:
   ```
   Validation error: Property 'zipCode' does not match pattern '^\\d{5}(-\\d{4})?$'
   ```
   Solution: Ensure string values match the required patterns.

4. **Numeric Constraints**:
   ```
   Validation error: Property 'price' must be >= 0
   ```
   Solution: Verify numeric values meet the defined constraints.

5. **Enum Validation**:
   ```
   Validation error: Property 'status' must be one of: ACTIVE, INACTIVE, PENDING
   ```
   Solution: Check that enum values match one of the allowed values.

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