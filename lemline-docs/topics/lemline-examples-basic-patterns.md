# Lemline Basic Workflow Patterns

This document provides examples of fundamental workflow patterns implemented in the Serverless Workflow DSL as supported by Lemline. These basic patterns serve as building blocks for creating more complex workflows.

## Sequential Task Execution

The simplest workflow pattern is a sequence of tasks executed one after another. This is achieved using the `do` construct.

### Single Task Example

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: call-http-shorthand-endpoint
  version: '0.1.0'
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
```

In this example, a single HTTP GET request is made to retrieve pet data. The workflow completes after this task is executed.

### Multiple Sequential Tasks Example

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: call-http-shorthand-endpoint
  version: '0.1.0'
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
  - buyPet:
      call: http
      with:
        method: put
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
        body: '${ . + { status: "sold" } }'
```

In this example, two HTTP calls are executed in sequence:
1. First, get pet information
2. Then, update the pet's status to "sold"

## Nested Task Execution

Tasks can be nested to group related operations and create hierarchical structures.

### Nested Tasks Example

```yaml
document:
  dsl: '1.0.0-alpha5'
  namespace: examples
  name: call-http-shorthand-endpoint
  version: '0.1.0'
do:
  - getPet:
      call: http
      with:
        method: get
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
  - nested:
      do:
        - init:
            set:
              startEvent: ${ $workflow.input[0] }
        - getPet:
            call: http
            with:
              method: get
              endpoint: https://petstore.swagger.io/v2/pet/{petId}
        - buyPet0:
            call: http
            with:
              method: put
              endpoint: https://petstore.swagger.io/v2/pet/{petId}
              body: '${ . + { status: "sold" } }'
  - buyPet:
      call: http
      with:
        method: put
        endpoint: https://petstore.swagger.io/v2/pet/{petId}
        body: '${ . + { status: "sold" } }'
```

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

```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: sample-workflow
  version: 0.1.0
do:
  - processOrder:
      switch:
        - case1:
            when: .orderType == "electronic"
            then: processElectronicOrder
        - case2:
            when: .orderType == "physical"
            then: processPhysicalOrder
        - default:
            then: handleUnknownOrderType
  - processElectronicOrder:
      set:
        validate: true
        status: fulfilled
      then: exit
  - processPhysicalOrder:
      set:
        inventory: clear
        items: 1
        address: Elmer St
      then: exit
  - handleUnknownOrderType:
      set:
        log: warn
        message: something's wrong
```

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

```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: for-example
  version: '0.1.0'
do:
  - checkup:
      for:
        each: pet
        in: .pets
        at: childIndex
      while: .vet != null
      do:
        - waitForCheckup:
            listen:
              to:
                one:
                  with:
                    type: com.fake.petclinic.pets.checkup.completed.v2
            output:
              as: '.pets + [{ "id": $pet.id }]'
```

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

```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: set
  version: '0.1.0'
schedule:
  on:
    one:
      with:
        type: io.serverlessworkflow.samples.events.trigger.v1
do: 
  - initialize:
      set:
        startEvent: ${ $workflow.input[0] }
```

This example demonstrates:
1. Setting a variable `startEvent` to the first element of the workflow input
2. The workflow is triggered by an event

Additional `set` capabilities:
- Set multiple variables in a single step
- Use JQ expressions for data transformation
- Reference existing variables and workflow context

## Advanced Set Example

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: advanced-set
  version: '0.1.0'
do:
  - initData:
      set:
        user:
          name: "John Doe"
          role: "admin"
          permissions: ["read", "write"]
        timestamp: ${ now() }
  - transformData:
      set:
        enrichedUser: ${ .user + { 
          lastAccess: .timestamp,
          isAdmin: .user.role == "admin",
          permissionCount: length(.user.permissions)
        } }
```

This advanced example shows:
1. Setting nested object structures
2. Using the JQ expression language for data manipulation
3. Combining and transforming existing variables
4. Using built-in functions like `now()` and `length()`

## Combining Patterns

These basic patterns can be combined to create more complex workflows.

### Combined Example

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: combined-patterns
  version: '0.1.0'
do:
  - initialize:
      set:
        items: [
          { "id": 1, "type": "digital", "name": "E-book" },
          { "id": 2, "type": "physical", "name": "Notebook" },
          { "id": 3, "type": "digital", "name": "Software" }
        ]
  - processItems:
      for:
        each: item
        in: .items
      do:
        - determineProcessingType:
            switch:
              - digitalItem:
                  when: $item.type == "digital"
                  then:
                    do:
                      - processDigital:
                          set:
                            processed: ${ $item + { "status": "processed", "downloadReady": true } }
              - physicalItem:
                  when: $item.type == "physical"
                  then:
                    do:
                      - processPhysical:
                          set:
                            processed: ${ $item + { "status": "processed", "shippingRequired": true } }
        - logResult:
            call: http
            with:
              method: post
              endpoint: https://example.com/log
              body: ${ $processed }
```

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