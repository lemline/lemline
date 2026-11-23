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
  dsl: '1.0.0'
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
  dsl: '1.0.0'
  namespace: examples
  name: basic-variable-assignment
  version: '1.0.0'
do:
  - setOrderDefaults:
      set:
        orderData:
          id: ${ .input.orderId || uuid() }
          createdAt: ${ new Date().toISOString() }
          status: "PENDING"
          customerName: ${ .input.customer.firstName + " " + .input.customer.lastName }
          priority: ${ .input.priority || "normal" }
```

### Data Transformation

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: data-transformation
  version: '1.0.0'
do:
  - retrieveUserData:
      call: function
      with:
        function: userService
        args:
          userId: ${ .input.userId }
      result: userData

  - transformUserProfile:
      set:
        profile:
          displayName: ${ .userData.firstName + " " + .userData.lastName }
          email: ${ .userData.email }
          joined: ${ new Date(.userData.createdTimestamp).toLocaleDateString() }
          membershipLevel: ${ 
            if(.userData.totalPurchases > 10000) 
              "platinum" 
            else if(.userData.totalPurchases > 5000) 
              "gold" 
            else if(.userData.totalPurchases > 1000) 
              "silver" 
            else 
              "bronze" 
          }
          tags: ${ .userData.interests || [] }
```

### Conditional Data Preparation

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: conditional-data-preparation
  version: '1.0.0'
do:
  - prepareApiRequest:
      set:
        apiRequest:
          endpoint: ${ .input.environment == "production" ? "https://api.example.com" : "https://staging-api.example.com" }
          headers:
            Authorization: ${ "Bearer " + .input.authToken }
            Content-Type: "application/json"
            Accept: "application/json"
          body: ${
            {
              "userId": .input.userId,
              "operation": .input.operation,
              "parameters": .input.parameters || {},
              "tracingId": uuid(),
              "timestamp": new Date().toISOString()
            }
          }
```

### Aggregating Results

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: result-aggregation
  version: '1.0.0'
do:
  - retrieveProductData:
      call: function
      with:
        function: productService
        args:
          productId: ${ .input.productId }
      result: productData
      
  - retrievePricing:
      call: function
      with:
        function: pricingService
        args:
          productId: ${ .input.productId }
          region: ${ .input.region }
      result: pricingData
      
  - retrieveInventory:
      call: function
      with:
        function: inventoryService
        args:
          productId: ${ .input.productId }
          warehouseId: ${ .input.warehouseId }
      result: inventoryData
      
  - aggregateProductInfo:
      set:
        enrichedProduct: ${
          {
            "id": .productData.id,
            "name": .productData.name,
            "description": .productData.description,
            "category": .productData.category,
            "pricing": {
              "basePrice": .pricingData.basePrice,
              "currency": .pricingData.currency,
              "discountPercentage": .pricingData.discountPercentage || 0,
              "finalPrice": .pricingData.finalPrice,
              "taxRate": .pricingData.taxRate
            },
            "inventory": {
              "available": .inventoryData.quantityAvailable > 0,
              "quantity": .inventoryData.quantityAvailable,
              "location": .inventoryData.warehouseLocation,
              "estimatedRestockDate": .inventoryData.quantityAvailable <= 0 ? .inventoryData.nextDeliveryDate : null
            },
            "lastUpdated": new Date().toISOString()
          }
        }
```

### Error Response Formatting

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: error-response-formatting
  version: '1.0.0'
do:
  - try:
      do:
        - processOrder:
            call: function
            with:
              function: orderProcessor
              args:
                order: ${ .input.order }
            result: processingResult
      catch:
        as: error
        do:
          - formatErrorResponse:
              set:
                errorResponse: ${
                  {
                    "success": false,
                    "errorCode": .error.code || "UNKNOWN_ERROR",
                    "message": .error.message || "An unexpected error occurred",
                    "details": .error.details || null,
                    "timestamp": new Date().toISOString(),
                    "requestId": .input.requestId || uuid(),
                    "suggestions": [
                      "Try again later",
                      "Contact support if the issue persists"
                    ]
                  }
                }
```

## Working with JSON Paths

The Set task leverages JQ-style JSON path expressions for powerful data manipulation:

```yaml
document:
  dsl: '1.0.0'
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
        status: ${ if(.input.approved) "APPROVED" else "PENDING" }
        
        # String operations
        upperCaseName: ${ .input.name | upper }
        formattedId: ${ .input.id | pad_left(10, "0") }
```

Data tasks are essential for maintaining clean data flow throughout your workflows and preparing data in exactly the right format for each subsequent task. The Set task in particular offers significant flexibility in how you transform and manipulate data within your workflow's execution. 