# Lemline Basic Workflow Patterns

This document provides examples of fundamental workflow patterns implemented in the Open Workflow DSL as supported by Lemline. These basic patterns serve as building blocks for creating more complex workflows.

## Sequential Task Execution

The simplest workflow pattern is a sequence of tasks executed one after another. This is achieved using the `do` construct.

### Single Task Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/single-task-example.yaml"/>

In this example, a single HTTP GET request is made to retrieve pet data. The workflow completes after this task is executed.

### Multiple Sequential Tasks Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/multiple-sequential-tasks-example.yaml"/>

In this example, two HTTP calls are executed in sequence:
1. First, get pet information
2. Then, update the pet's status to "sold"

## Nested Task Execution

Tasks can be nested to group related operations and create hierarchical structures.

### Nested Tasks Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/nested-tasks-example.yaml"/>

This example demonstrates:
1. A top-level task (`getPet`)
2. A nested block containing three tasks (`init`, `getPet`, `buyPet0`)
3. Another top-level task (`buyPet`)

Nesting is useful for:
- Grouping related operations
- Localizing variable scope
- Creating reusable task patterns

## Conditional Logic with Switch

The `switch` construct allows workflows to implement conditional logic, executing different tasks based on data conditions.

### Switch Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/switch-example.yaml"/>

This example demonstrates:
1. A switch statement evaluating `.orderType`
2. Three different paths based on the condition
3. Each path setting different variables and potentially ending the workflow
4. A default case for handling unknown types

Key aspects of `switch`:
- Multiple conditions can be evaluated in sequence
- The first matching condition is executed
- The `default` case handles situations where no conditions match
- `then` can direct flow to other named tasks in the workflow

## Loop Patterns

Workflows often need to iterate over collections of data or repeat tasks until certain conditions are met.

### For Loop Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/for-loop-example.yaml"/>

This example demonstrates:
1. Iterating over a collection (`.pets`)
2. The current item is accessible as `$pet`
3. The index is accessible as `$childIndex`
4. A condition (`while: .vet != null`) controls continuation
5. Inside the loop, an event listener waits for a specific event
6. The output is transformed for each iteration

Key aspects of `for`:
- Iterates over arrays or objects
- Can track iteration index with the `at` property
- Can include additional conditions with `while`
- Contains a `do` block with tasks to execute for each iteration

## Data Manipulation with Set

The `set` construct allows workflows to create or modify variables.

### Set Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/set-example.yaml"/>

This example demonstrates:
1. Setting a variable `startEvent` to the first element of the workflow input
2. The workflow is triggered by an event

Additional `set` capabilities:
- Set multiple variables in a single step
- Use JQ expressions for data transformation
- Reference existing variables and workflow context

## Advanced Set Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/advanced-set-example.yaml"/>

This advanced example shows:
1. Setting nested object structures
2. Using the JQ expression language for data manipulation
3. Combining and transforming existing variables
4. Using built-in functions like `now()` and `length()`

## Combining Patterns

These basic patterns can be combined to create more complex workflows.

### Combined Example

<code-block lang="yaml" src="workflows/lemline-examples-basic-patterns/combined-example.yaml"/>

This complex example demonstrates:
1. Initializing a data structure with `set`
2. Iterating over the data with `for`
3. Using `switch` for conditional processing based on item type
4. Nesting `do` blocks within conditions
5. Transforming data based on conditions
6. Using the transformed data in subsequent tasks

## Best Practices

When implementing these basic patterns:

1. **Descriptive task names**: Use clear, meaningful names for tasks to make workflows self-documenting
2. **Appropriate granularity**: Break complex tasks into manageable steps, but avoid excessive fragmentation
3. **Consistent data structure**: Maintain a predictable data structure throughout the workflow
4. **Error handling**: Consider adding try-catch blocks for operations that may fail
5. **Data validation**: Validate inputs early in the workflow
6. **Modular design**: Group related tasks for better organization and potential reuse

## Conclusion

These basic workflow patterns form the foundation for building complex business processes in Lemline. By understanding and combining these patterns, you can create workflows that are:

- Easy to understand
- Maintainable
- Adaptable to changing requirements
- Capable of handling complex business logic

For examples integrating with external services, see the [Integration Examples](lemline-examples-integrations.md) document.