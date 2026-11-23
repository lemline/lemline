---
title: How to pass data between tasks
---

# How to pass data between tasks

This guide explains how to pass and transform data between tasks in Lemline workflows. You'll learn how data flows through a workflow, how to access data from previous tasks, and how to use expressions to transform data.

## Understanding Data Flow in Lemline

Data flow is a fundamental aspect of Lemline workflows. Each task has:

- **Input**: Data available to the task when it executes
- **Output**: Data produced by the task after execution
- **Transformations**: Ways to modify data between input and output

The key concepts to understand are:

1. **Task Input/Output Chain**: Each task's output becomes the input to the next task
2. **Workflow Context**: A shared data space that tasks can read from and write to
3. **JQ Expressions**: Used to transform and manipulate data
4. **Scoping Rules**: Determine what data is accessible in different parts of the workflow

## Basic Data Passing Between Tasks

In its simplest form, data flows automatically from one task to the next:

```yaml
- name: FirstTask
  type: set
  data:
    customer:
      name: "John Doe"
      email: "john@example.com"
  next: SecondTask

- name: SecondTask
  type: set
  data:
    greeting: "Hello, .customer.name!"
    contact: ".customer.email"
  next: ThirdTask

- name: ThirdTask
  type: set
  data:
    message: ".greeting The confirmation will be sent to .contact"
  next: FinalTask
```

In this example:
1. `FirstTask` sets a `customer` object
2. `SecondTask` can access that object using `.customer`
3. `ThirdTask` can access the data set by both previous tasks

## Data Transformation Chain

Each task in Lemline follows this data transformation process:

1. **Raw Input**: The output from the previous task
2. **Transformed Input**: After applying input transformations
3. **Task Processing**: Task-specific operations
4. **Raw Output**: The initial result of the task
5. **Transformed Output**: After applying output transformations

This chain provides multiple opportunities to shape and refine data as it flows through the workflow.

## Accessing Previous Task Data

### Referencing Data From Previous Tasks

You can access data from previous tasks using dot notation:

```yaml
- name: GatherUserData
  type: set
  data:
    userId: "12345"
    username: "johndoe"
  next: FetchUserPreferences

- name: FetchUserPreferences
  type: call
  function: preferencesService
  data:
    userId: ".userId"  # Referencing data from GatherUserData
  next: CombineData

- name: CombineData
  type: set
  data:
    user:
      id: ".userId"                 # From GatherUserData
      name: ".username"             # From GatherUserData
      preferences: ".preferences"   # From FetchUserPreferences
  next: ProcessCombinedData
```

### Accessing Specific Task Results by Name

You can explicitly reference data from a specific task using `$WORKFLOW.TaskName`:

```yaml
- name: FetchProduct
  type: call
  function: productService
  data:
    productId: ".productId"
  next: FetchPricing

- name: FetchPricing
  type: call
  function: pricingService
  data:
    productId: ".productId"
  next: CombineResults

- name: CombineResults
  type: set
  data:
    product: "$WORKFLOW.FetchProduct"          # Entire result from FetchProduct
    pricing: "$WORKFLOW.FetchPricing"          # Entire result from FetchPricing
    combinedData:
      id: "$WORKFLOW.FetchProduct.id"
      name: "$WORKFLOW.FetchProduct.name"
      description: "$WORKFLOW.FetchProduct.description"
      price: "$WORKFLOW.FetchPricing.price"
      discount: "$WORKFLOW.FetchPricing.discount"
  next: NextTask
```

This is especially useful when task outputs might overwrite data with the same names.

### Accessing Data from Complex Task Types

Some task types, like `fork` or `for`, produce structured outputs:

```yaml
- name: ParallelProcessing
  type: fork
  branches:
    - name: UserBranch
      tasks:
        - name: GetUserData
          type: call
          function: userService
          data:
            userId: ".userId"
    - name: OrderBranch
      tasks:
        - name: GetOrderHistory
          type: call
          function: orderService
          data:
            userId: ".userId"
  next: CombineResults

- name: CombineResults
  type: set
  data:
    userData: "$WORKFLOW.UserBranch.result"      # Result from the UserBranch
    orderHistory: "$WORKFLOW.OrderBranch.result" # Result from the OrderBranch
    dashboard:
      user:
        name: "$WORKFLOW.UserBranch.result.name"
        email: "$WORKFLOW.UserBranch.result.email"
      recentOrders: "$WORKFLOW.OrderBranch.result.orders[0:3]"
  next: NextTask
```

For loops, access array results:

```yaml
- name: ProcessItems
  type: for
  iterator:
    collect: ".items"
    as: "item"
  do:
    - name: ProcessItem
      type: call
      function: itemProcessor
      data:
        itemId: ".item.id"
  next: SummarizeResults

- name: SummarizeResults
  type: set
  data:
    processedItems: "$WORKFLOW.ProcessItems.results"  # Array of all results
    itemCount: "$WORKFLOW.ProcessItems.results | length"
    successCount: "$WORKFLOW.ProcessItems.results | map(select(.status == \"SUCCESS\")) | length"
  next: NextTask
```

## Working with Input Data

### Workflow Input

When you start a workflow, you can provide input data. This input is accessible as `$WORKFLOW.input`:

```yaml
- name: ProcessWorkflowInput
  type: set
  data:
    requestId: "$WORKFLOW.input.requestId"
    customerId: "$WORKFLOW.input.customerId"
    items: "$WORKFLOW.input.items"
    validInput: "$WORKFLOW.input.requestId != null && $WORKFLOW.input.customerId != null"
  next: ValidateInput
```

### Default Values and Nulls

You can provide default values when data might be missing:

```yaml
- name: SetDefaults
  type: set
  data:
    username: ".username || 'Guest'"  # Default to 'Guest' if username is null
    quantity: ".quantity || 1"        # Default to 1 if quantity is null
    discount: ".discount // 0"        # Default to 0 if discount is null or undefined
  next: NextTask
```

### Conditional Data Access

Use conditional expressions to access data safely:

```yaml
- name: ProcessConditionalData
  type: set
  data:
    hasAddress: ".customer.address != null"
    shippingAddress: "if .hasAddress then .customer.address else .defaultAddress end"
    needsShipping: ".items | map(select(.digital == false)) | length > 0"
  next: NextTask
```

## Transforming Data with JQ Expressions

Lemline uses JQ for data transformations. Here are common transformation patterns:

### String Manipulation

```yaml
- name: StringTransformations
  type: set
  data:
    name: ".customer.name"
    uppercaseName: ".name | ascii_upcase"
    greeting: "\"Hello, \" + .name + \"!\""
    truncatedDesc: ".product.description[0:50] + \"...\""
    slug: ".name | ascii_downcase | gsub(\"\\s+\"; \"-\")"
  next: NextTask
```

### Numeric Operations

```yaml
- name: NumericTransformations
  type: set
  data:
    price: ".product.price"
    quantity: ".order.quantity"
    subtotal: ".price * .quantity"
    tax: ".subtotal * 0.08"
    total: ".subtotal + .tax"
    roundedTotal: ".total | round"
    formattedPrice: "\"$\" + (.price | tostring)"
  next: NextTask
```

### Array Operations

```yaml
- name: ArrayTransformations
  type: set
  data:
    items: ".order.items"
    itemCount: ".items | length"
    itemIds: ".items | map(.id)"
    subtotals: ".items | map(.price * .quantity)"
    totalAmount: ".subtotals | add"
    hasExpensiveItems: ".items | map(.price) | max > 100"
    expensive: ".items | map(select(.price > 100))"
    sortedItems: ".items | sort_by(.price)"
  next: NextTask
```

### Object Transformations

```yaml
- name: ObjectTransformations
  type: set
  data:
    customer: ".order.customer"
    enrichedCustomer: ".customer + {
      fullName: .customer.firstName + \" \" + .customer.lastName,
      isVIP: .customer.totalOrders > 10
    }"
    orderSummary: {
      id: ".order.id",
      customer: ".customer.fullName",
      total: ".order.total",
      date: ".order.date"
    }
  next: NextTask
```

### Filtering and Selecting

```yaml
- name: FilteringData
  type: set
  data:
    allItems: ".inventory.items"
    inStockItems: ".allItems | map(select(.stock > 0))"
    lowStockItems: ".allItems | map(select(.stock > 0 and .stock < 10))"
    outOfStockItems: ".allItems | map(select(.stock == 0))"
    categoryItems: ".allItems | map(select(.category == \"electronics\"))"
  next: NextTask
```

### Grouping and Aggregation

```yaml
- name: GroupingData
  type: set
  data:
    orders: ".customerOrders"
    ordersByStatus: ".orders | group_by(.status)"
    statusCounts: ".orders | group_by(.status) | map({status: .[0].status, count: length})"
    totalByMonth: ".orders | group_by(.month) | map({month: .[0].month, total: map(.amount) | add})"
  next: NextTask
```

## Exporting Data to Global Context

The `export` property allows you to save data to the global workflow context, making it available to all subsequent tasks:

```yaml
- name: ProcessOrderData
  type: call
  function: orderService
  data:
    orderId: ".orderId"
  export:
    as:
      order: "."                        # Export entire response as 'order'
      customer: ".customer"             # Export customer object
      items: ".items"                   # Export items array
  next: ProcessShipping
```

Exported data is available via `$WORKFLOW.order`, `$WORKFLOW.customer`, etc.

## Validating Data with Schemas

You can validate data against schemas when passing it between tasks:

### Input Validation

```yaml
- name: ValidatePaymentData
  type: call
  function: paymentService
  data:
    schema:
      type: object
      required: ["amount", "currency", "paymentMethod"]
      properties:
        amount:
          type: number
          minimum: 0.01
        currency:
          type: string
          enum: ["USD", "EUR", "GBP"]
        paymentMethod:
          type: object
          required: ["type", "token"]
    order:
      amount: ".order.total"
      currency: ".order.currency"
      paymentMethod:
        type: ".payment.type"
        token: ".payment.token"
  next: ProcessPaymentResult
```

### Output Validation

```yaml
- name: ProcessCustomerData
  type: set
  data:
    schema:
      type: object
      required: ["id", "name", "email"]
      properties:
        id:
          type: string
        name:
          type: string
          minLength: 1
        email:
          type: string
          format: email
    customer:
      id: ".userId"
      name: ".fullName"
      email: ".emailAddress"
  next: NextTask
```

## Real-World Example: Order Processing Data Flow

Here's a complete example of data passing through an order processing workflow:

```yaml
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ReceiveOrderData
tasks:
  - name: ReceiveOrderData
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      metadata: "$WORKFLOW.input.metadata || {}"
      createdAt: "$WORKFLOW.startTime"
    next: ValidateOrder
  
  - name: ValidateOrder
    type: set
    data:
      hasItems: ".items | length > 0"
      hasCustomer: ".customerId != null"
      isValid: ".hasItems && .hasCustomer"
      validationMessage: "if .isValid then \"Order validated successfully\" else \"Invalid order: \" + (if .hasItems == false then \"No items\" else \"\" end) + (if .hasCustomer == false then \"No customer\" else \"\" end) end"
    next: CheckValidOrder
  
  - name: CheckValidOrder
    type: switch
    conditions:
      - condition: ".isValid == false"
        next: RejectOrder
      - condition: true
        next: EnrichOrderData
  
  - name: EnrichOrderData
    type: fork
    branches:
      - name: CustomerBranch
        tasks:
          - name: GetCustomerData
            type: call
            function: customerService
            data:
              customerId: ".customerId"
              include: "profile,addresses,payment_methods"
      - name: ProductBranch
        tasks:
          - name: GetProductDetails
            type: call
            function: productService
            data:
              productIds: ".items | map(.productId)"
    next: CalculateOrderTotals
  
  - name: CalculateOrderTotals
    type: set
    data:
      customer: "$WORKFLOW.CustomerBranch.result"
      products: "$WORKFLOW.ProductBranch.result.products"
      
      # Map product details to order items
      enrichedItems: ".items | map(. + {
        product: $WORKFLOW.ProductBranch.result.products | map(select(.id == .productId)) | .[0],
        subtotal: .quantity * ($WORKFLOW.ProductBranch.result.products | map(select(.id == .productId)) | .[0].price)
      })"
      
      # Calculate totals
      subtotal: ".enrichedItems | map(.subtotal) | add"
      taxRate: ".customer.taxExempt ? 0 : 0.08"
      tax: ".subtotal * .taxRate"
      
      # Check for discounts
      hasLoyaltyDiscount: ".customer.loyaltyTier == \"GOLD\" || .customer.loyaltyTier == \"PLATINUM\""
      loyaltyDiscountRate: "if .customer.loyaltyTier == \"PLATINUM\" then 0.10 else if .customer.loyaltyTier == \"GOLD\" then 0.05 else 0 end end"
      loyaltyDiscount: ".subtotal * .loyaltyDiscountRate"
      
      # Final calculations
      total: ".subtotal + .tax - .loyaltyDiscount"
      
      # Format for display
      formattedSubtotal: "\"$\" + (.subtotal | tostring)"
      formattedTax: "\"$\" + (.tax | tostring)"
      formattedDiscount: "\"$\" + (.loyaltyDiscount | tostring)"
      formattedTotal: "\"$\" + (.total | tostring)"
      
      # Summary
      summary: {
        orderId: ".orderId",
        customer: {
          id: ".customer.id",
          name: ".customer.fullName",
          email: ".customer.email",
          loyaltyTier: ".customer.loyaltyTier"
        },
        items: ".enrichedItems | map({
          productId: .productId,
          productName: .product.name,
          quantity: .quantity,
          unitPrice: .product.price,
          subtotal: .subtotal
        })",
        pricing: {
          subtotal: ".subtotal",
          tax: ".tax",
          discount: ".loyaltyDiscount",
          total: ".total"
        },
        shipping: {
          address: ".customer.defaultShippingAddress",
          method: "STANDARD"
        },
        createdAt: ".createdAt"
      }
    export:
      as:
        orderSummary: ".summary"
    next: ProcessPayment
  
  - name: ProcessPayment
    type: call
    function: paymentService
    data:
      customerId: ".customer.id"
      paymentMethodId: ".customer.defaultPaymentMethod.id"
      amount: ".total"
      currency: "USD"
      description: "Order .orderId"
    next: EvaluatePaymentResult
  
  - name: EvaluatePaymentResult
    type: switch
    conditions:
      - condition: ".status == \"succeeded\""
        next: CreateShipment
      - condition: true
        next: HandlePaymentFailure
  
  - name: CreateShipment
    type: call
    function: shippingService
    data:
      orderId: ".orderId"
      customerId: ".customer.id"
      items: ".enrichedItems | map({
        productId: .productId,
        quantity: .quantity,
        weight: .product.weight,
        dimensions: .product.dimensions
      })"
      shippingAddress: ".customer.defaultShippingAddress"
      shippingMethod: "STANDARD"
    next: FinalizeOrder
  
  - name: FinalizeOrder
    type: set
    data:
      finalOrder: {
        orderId: ".orderId",
        status: "CONFIRMED",
        customer: ".customer.fullName",
        items: ".enrichedItems | length",
        total: ".formattedTotal",
        paymentId: ".transactionId",
        shipmentId: ".shipmentId",
        trackingNumber: ".trackingNumber",
        estimatedDelivery: ".estimatedDelivery"
      }
      confirmation: "Order .orderId confirmed for .customer.fullName. Total: .formattedTotal. Tracking: .trackingNumber"
    next: SendOrderConfirmation
  
  - name: SendOrderConfirmation
    type: call
    function: notificationService
    data:
      recipient: ".customer.email"
      subject: "Order Confirmation: .orderId"
      message: ".confirmation"
      details: ".finalOrder"
    end: true
  
  - name: RejectOrder
    type: set
    data:
      status: "REJECTED"
      reason: ".validationMessage"
    end: true
  
  - name: HandlePaymentFailure
    type: set
    data:
      status: "PAYMENT_FAILED"
      errorMessage: ".errorMessage || \"Payment processing failed\""
      declineReason: ".declineReason || \"Unknown error\""
    end: true
```

This workflow demonstrates many data passing patterns:
1. Accessing workflow input
2. Validating and transforming data
3. Using fork to gather data in parallel
4. Combining data from multiple sources
5. Complex calculations and transformations
6. Exporting data to the global context
7. Conditional data processing
8. Structuring final output data

## Best Practices for Data Passing

1. **Use Explicit Naming**: Give tasks clear names for easier referencing
2. **Structure Data Hierarchically**: Organize related data in nested objects
3. **Transform Early**: Clean and normalize data as early as possible
4. **Validate Inputs**: Use schema validation for critical inputs
5. **Use Default Values**: Handle missing data gracefully with defaults
6. **Leverage JQ Features**: Use JQ's powerful functions for complex transformations
7. **Export Judiciously**: Only export data that's needed across multiple tasks
8. **Keep Schema Validation**: Validate both inputs and outputs for critical operations
9. **Document Data Structures**: Comment on complex data structures for clarity
10. **Avoid Deep Nesting**: Keep data structures reasonably flat for readability

## Troubleshooting Data Issues

### Missing Data

If data is unexpectedly missing:
- Check that you're referencing the correct task name
- Verify that the path to the data is correct
- Ensure the previous task actually generated the expected data
- Add default values with the `||` operator

### Type Errors

If you're getting type-related errors:
- Check if you're trying to use operations on the wrong data type
- Use type conversion functions like `tostring`, `tonumber`
- Add explicit type checking in your expressions

### Expression Debugging

To debug complex expressions:
- Break complex expressions into smaller parts
- Add intermediate variables to inspect values
- Use a `set` task with the expression to examine its result

## Next Steps

- Learn about [using JQ runtime expressions](lemline-howto-jq.md)
- Explore [how to validate inputs and outputs with schemas](lemline-howto-schemas.md)
- Understand [how to use try/catch for error handling](lemline-howto-try-catch.md)