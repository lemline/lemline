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

## Working with Workflow Input

### Defining Workflow Input Schema

You can define an input schema at the workflow level to validate incoming data:

<code-block lang="yaml" src="workflows/lemline-howto-io/defining-workflow-input-schema.yaml"/>

This schema ensures that workflow input contains the required fields with the correct data types and formats.

### Accessing Workflow Input

You can access the original workflow input in any task using `$workflow.input`:

<code-block lang="yaml" src="workflows/lemline-howto-io/accessing-workflow-input.yaml" include-lines="7-13"/>

### Default Input Values

You can provide default values for workflow input fields:

<code-block lang="yaml" src="workflows/reference/default-workflow-input.yaml"/>

The `input.from` expression of the first task merges defaults with the workflow input. Values supplied by the caller take precedence. Lemline does not apply a workflow-level `input.from` yet: put the expression on the first task.

## Task Input Processing

### Implicit Input

By default, each task receives the output of the previous task as its input:

<code-block lang="yaml" src="workflows/lemline-howto-io/implicit-input.yaml" include-lines="7-16"/>

### Input Transformation

You can transform the input data before task execution:

<code-block lang="yaml" src="workflows/lemline-howto-io/input-transformation.yaml" include-lines="7-20"/>

The `input.from` field lets you reshape the incoming data before it's used in the task.

### Input Schema Validation

You can validate task input against a schema:

<code-block lang="yaml" src="workflows/lemline-howto-io/input-schema-validation.yaml" include-lines="7-40"/>

If the input doesn't match the schema, a validation error is raised.

## Task Output Processing

### Basic Output

The output of a `set` task is the object defined under `set`:

<code-block lang="yaml" src="workflows/lemline-howto-io/basic-output.yaml" include-lines="7-12"/>

This task produces an output object with `status`, `result`, and `timestamp` fields.

### Output Transformation

You can transform the output data before passing it to the next task:

<code-block lang="yaml" src="workflows/lemline-howto-io/output-transformation.yaml" include-lines="7-14"/>

The `output.as` field reshapes the task result before it's passed to the next task.

### Output Schema Validation

You can validate task output against a schema:

<code-block lang="yaml" src="workflows/lemline-howto-io/output-schema-validation.yaml" include-lines="7-35"/>

If the output doesn't match the schema, a validation error is raised.

## Exporting Data to Global Context

### Basic Export

The `export` field lets you promote data to the global workflow context:

<code-block lang="yaml" src="workflows/lemline-howto-io/basic-export.yaml" include-lines="7-14"/>

Later tasks can read the exported customer data from `$context.customer`.

### Exporting Selected Fields

You can export specific fields rather than the entire output:

<code-block lang="yaml" src="workflows/lemline-howto-io/exporting-selected-fields.yaml" include-lines="7-16"/>

This exports only the selected fields to the global context.

### Export with Transformation

You can transform data during export:

<code-block lang="yaml" src="workflows/lemline-howto-io/export-with-transformation.yaml" include-lines="7-16"/>

This exports the original products array plus derived data structures.

### Export Schema Validation

You can validate exported data against a schema:

<code-block lang="yaml" src="workflows/lemline-howto-io/export-schema-validation.yaml" include-lines="7-37"/>

This ensures the exported data meets the specified schema requirements.

## Comprehensive Example: Order Processing

This workflow validates the input, routes the order based on its total, and
returns the order ID and processing status.

<code-block lang="yaml" src="workflows/reference/validate-and-route-order.yaml"/>

## Advanced Patterns

### Chain of Responsibility

Chain multiple data transformations for complex processing:

<code-block lang="yaml" src="workflows/lemline-howto-io/chain-of-responsibility.yaml" include-lines="7-21"/>

### Selective Processing with Condition-Based Export

Export different data based on conditions:

<code-block lang="yaml" src="workflows/lemline-howto-io/selective-processing-with-condition-based-export.yaml" include-lines="7-16"/>

### Parameterized Tasks

Use exported data as task parameters:

<code-block lang="yaml" src="workflows/reference/share-order-context.yaml"/>

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
