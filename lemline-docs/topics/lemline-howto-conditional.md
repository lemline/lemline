---
title: How to define conditional branches (switch)
---

# How to define conditional branches (switch)

This guide shows you how to implement conditional logic in your Lemline workflows using the `switch` task. You'll learn how to create dynamic branching based on workflow data, evaluate conditions using JQ expressions, and implement complex decision trees.

## Understanding the Switch Task

The `switch` task lets you create conditional branches in your workflow based on runtime data. It evaluates a list of conditions in order and directs the workflow to the first matching branch.

## Basic Switch Structure

Here's the basic structure of a `switch` task:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/basic-switch-structure.yaml" include-lines="7-16"/>

Each condition is a JQ expression that evaluates to a boolean. The first condition that evaluates to `true` determines the next task to execute.

## Simple Binary Decision

Let's start with a simple yes/no decision pattern:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/simple-binary-decision.yaml" include-lines="7-19"/>

This example checks inventory availability and branches to different tasks based on the result.

## Multiple Conditions

You can define multiple conditions for more complex decision trees:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/multiple-conditions.yaml" include-lines="7-19"/>

This example categorizes leads based on score ranges.

## Using Complex Expressions

JQ expressions in conditions can be complex logical statements:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/using-complex-expressions.yaml" include-lines="7-25"/>

This example routes customer requests based on multiple criteria.

## Default Fallback

Always include a default condition as the last one in your switch task to ensure the workflow always has a path forward:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/default-fallback.yaml" include-lines="7-19"/>

The last condition using `true` ensures there's always a matching path, even if none of the explicit conditions match.

## Working with Arrays and Objects

JQ expressions can work with complex data structures:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/working-with-arrays-and-objects.yaml" include-lines="7-19"/>

This example determines shipping method based on order weight, shipping preferences, and customer tier.

## Combining Multiple Variables

You can reference multiple variables in a single condition:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/combining-multiple-variables.yaml" include-lines="7-19"/>

This example determines discounts based on customer loyalty, order total, and item categories.

## Nested Decision Making

For complex decision making, you can use multiple switch tasks in sequence:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/nested-decision-making.yaml" include-lines="7-23"/>

This example implements a two-level decision tree for risk evaluation.

## Using Data Transformations before Switching

You can use a `set` task to prepare data for a switch decision:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/using-data-transformations-before-switching.yaml" include-lines="7-25"/>

This pattern improves readability by computing derived values before the switch.

## Error Handling in Switch Tasks

If an expression in a condition results in an error, Lemline will treat it as `false`:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/error-handling-in-switch-tasks.yaml" include-lines="7-16"/>

This pattern handles cases where properties might be missing.

## Combining Switch with Try/Catch

For robust conditional handling, combine switch with try/catch:

<code-block lang="yaml" src="workflows/reference/parallel-checks-with-fallback.yaml"/>

This pattern provides comprehensive error handling around conditional logic.

## Real-World Example: Order Processing

Here's a complete example of an order processing workflow with multiple decision points:

<code-block lang="yaml" src="workflows/lemline-howto-conditional/real-world-example-order-processing.yaml"/>

## Best Practices for Switch Tasks

1. **Order conditions from specific to general**: Put more specific conditions first
2. **Always include a default case**: Use `condition: true` as the last condition
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