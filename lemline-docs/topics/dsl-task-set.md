# Set

## Purpose

The `Set` task is used to dynamically create or modify data within the workflow's execution flow. Its primary function
is to define the exact output of the task by evaluating a specified structure, often
using [Runtime Expressions](dsl-runtime-expressions.md) to incorporate data from the task's input or the workflow
context.

It's commonly used for:

* Initializing data structures.
* Assigning values to variables.
* Transforming or restructuring data between tasks when the standard `input.from` or `output.as` transformations are
  insufficient or less clear.
* Explicitly defining the data to be passed forward.

## Basic Usage

Here's a simple example of using `Set` to create a new object:

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: set-basic
  version: '1.0.0'
do:
  - initialData:
      set:
        user:
          name: "Alice"
          id: 123
        status: "active"
  - processData: # Input to this task is the output of initialData
      call: someFunction
      with:
        userData: "${ .user }"
        currentStatus: "${ .status }"

# Output of 'initialData' task: { "user": { "name": "Alice", "id": 123 }, "status": "active" }
```

In this example, the `initialData` task directly defines an object with `user` and `status` fields.
This object becomes the output of `initialData` and is subsequently passed as input to the `processData` task.

Here's another example using a runtime expression to combine input data:

```yaml
do:
  - combineNames: # Assume input is { "firstName": "Bob", "lastName": "Smith" }
      set:
        fullName: "${ .firstName + ' ' + .lastName }"
        originalInput: "${ . }" # Include original input if needed

# Output of 'combineNames': { "fullName": "Bob Smith", "originalInput": { "firstName": "Bob", "lastName": "Smith" } }
```

## Configuration Options

### `set` (Object, Required)

This mandatory property defines the structure and content of the task's output.

The value provided for `set` is evaluated as a template where [Runtime Expressions](dsl-runtime-expressions.md) (e.g.,
`${.fieldName}`, `${ $context.someValue }`) can be used.

The result of evaluating this structure becomes the **raw output** of the `Set` task.

```yaml
set:
  # Static values
  configValue: 100
  # Values from transformed input
  inputValue: "${ .inputField }"
  processedValue: "${ (.inputField * 2) + $context.offset }" # Combine input and context
  # Nested structures
  details:
    timestamp: "${ $runtime.now.iso8601 }" # Using runtime variable
    source: "workflowX"
```

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Set` task's primary purpose is to generate its `rawOutput` based on the `set` configuration. Standard `output.as` and `export.as` then process this generated output.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>


## Set Task Examples

### Basic Variable Assignment

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: basic-variable-assignment
  version: '1.0.0'
do:
  - setOrderDefaults:
      set:
        orderData:
          id: ${ .input.orderId // $workflow.id }
          createdAt: ${ (now | todateiso8601) }
          status: "PENDING"
          customerName: ${ .input.customer.firstName + " " + .input.customer.lastName }
          priority: ${ .input.priority // "normal" }
```

### Data Transformation

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: data-transformation
  version: '1.0.0'
do:
  - retrieveUserData:
      call: userService
      with:
        userId: '${ .input.userId }'
      output:
        as: '${ $input + { "userData": . } }'
  - transformUserProfile:
      set:
        profile:
          displayName: '${ .userData.firstName + " " + .userData.lastName }'
          email: '${ .userData.email }'
          joined: '${ .userData.createdTimestamp | fromdateiso8601 | strftime("%Y-%m-%d") }'
          membershipLevel: '${ if .userData.totalPurchases > 10000 then "platinum" elif .userData.totalPurchases > 5000 then
            "gold" elif .userData.totalPurchases > 1000 then "silver" else "bronze" end }'
          tags: '${ .userData.interests // [] }'
```

### Conditional Data Preparation

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: conditional-data-preparation
  version: '1.0.0'
do:
  - prepareApiRequest:
      set:
        apiRequest:
          endpoint: '${ if .input.environment == "production" then "https://api.example.com" else "https://staging-api.example.com"
            end }'
          headers:
            Authorization: '${ "Bearer " + .input.authToken }'
            Content-Type: "application/json"
            Accept: "application/json"
          body: '${ { "userId": (.input.userId), "operation": (.input.operation), "parameters": (.input.parameters // {}),
            "tracingId": ($workflow.id), "timestamp": ((now | todateiso8601))} }'
```

### Aggregating Results

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: result-aggregation
  version: '1.0.0'
do:
  - retrieveProductData:
      call: productService
      with:
        productId: '${ .input.productId }'
      output:
        as: '${ $input + { "productData": . } }'
  - retrievePricing:
      call: pricingService
      with:
        productId: '${ .input.productId }'
        region: '${ .input.region }'
      output:
        as: '${ $input + { "pricingData": . } }'
  - retrieveInventory:
      call: inventoryService
      with:
        productId: '${ .input.productId }'
        warehouseId: '${ .input.warehouseId }'
      output:
        as: '${ $input + { "inventoryData": . } }'
  - aggregateProductInfo:
      set:
        enrichedProduct: '${ { "id": (.productData.id), "name": (.productData.name), "description": (.productData.description),
          "category": (.productData.category), "pricing": ({ "basePrice": (.pricingData.basePrice), "currency": (.pricingData.currency),
          "discountPercentage": (.pricingData.discountPercentage // 0), "finalPrice": (.pricingData.finalPrice), "taxRate":
          (.pricingData.taxRate)}), "inventory": ({ "available": (.inventoryData.quantityAvailable > 0), "quantity": (.inventoryData.quantityAvailable),
          "location": (.inventoryData.warehouseLocation), "estimatedRestockDate": (if .inventoryData.quantityAvailable <=
          0 then .inventoryData.nextDeliveryDate else null end)}), "lastUpdated": ((now | todateiso8601))} }'
```

### Error Response Formatting

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: error-response-formatting
  version: '1.0.0'
do:
  - try:
      catch:
        as: error
        do:
          - formatErrorResponse:
              set:
                errorResponse: '${ { "success": (false), "errorCode": (.error.code // "UNKNOWN_ERROR"), "message": (.error.message
                  // "An unexpected error occurred"), "details": (.error.details // null), "timestamp": ((now | todateiso8601)),
                  "requestId": (.input.requestId // $workflow.id), "suggestions": ([ "Try again later", "Contact support if
                  the issue persists" ])} }'
      try:
        - processOrder:
            call: orderProcessor
            with:
              order: '${ .input.order }'
            output:
              as: '${ $input + { "processingResult": . } }'
```

## Working with JSON Paths

The Set task leverages JQ-style JSON path expressions for powerful data manipulation:

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: json-path-examples
  version: '1.0.0'
do:
  - manipulateData:
      set:
        # Access nested properties
        customerEmail: ${ .input.order.customer.contactInfo.email }

        # Array operations
        firstItem: ${ .input.items[0] }
        itemCount: ${ .input.items | length }

        # Filtering arrays
        expensiveItems: ${ .input.products[.price > 100] }

        # Mapping arrays
        productNames: ${ .input.products[].name }

        # Combining data
        combinedList: ${ .list1 + .list2 }

        # Conditional assignment
        status: ${ if .input.approved then "APPROVED" else "PENDING" end }

        # String operations
        upperCaseName: ${ .input.name | ascii_upcase }
        formattedId: ${ .input.id | tostring | if length < 10 then ("0000000000" + .)[-10:] else . end }
```

Data tasks are essential for maintaining clean data flow throughout your workflows and preparing data in exactly the right format for each subsequent task. The Set task in particular offers significant flexibility in how you transform and manipulate data within your workflow's execution. 