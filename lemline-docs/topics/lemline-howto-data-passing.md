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
  - FirstTask:
      set:
        customer:
          name: "John Doe"
          email: "john@example.com"
      then: SecondTask
  - SecondTask:
      set:
        greeting: ${ "Hello, \(.customer.name)!" }
        contact: ${ .customer.email }
      then: ThirdTask
  - ThirdTask:
      set:
        message: ${ .greeting The confirmation will be sent to .contact }
      then: FinalTask
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
  - GatherUserData:
      set:
        userId: "12345"
        username: "johndoe"
      then: FetchUserPreferences
  - FetchUserPreferences:
      call: preferencesService
      with:
        userId: ${ .userId }
      then: CombineData
  - CombineData:
      set:
        user:
          id: ${ .userId }
          name: ${ .username }
          preferences: ${ .preferences }
      then: ProcessCombinedData
```

### Accessing Specific Task Results by Name

You can explicitly reference data from a specific task using `$WORKFLOW.TaskName`:

```yaml
  - FetchProduct:
      call: productService
      with:
        productId: ${ .productId }
      then: FetchPricing
  - FetchPricing:
      call: pricingService
      with:
        productId: ${ .productId }
      then: CombineResults
  - CombineResults:
      set:
        product: ${ $context.FetchProduct }
        pricing: ${ $context.FetchPricing }
        combinedData:
          id: ${ $context.FetchProduct.id }
          name: ${ $context.FetchProduct.name }
          description: ${ $context.FetchProduct.description }
          price: ${ $context.FetchPricing.price }
          discount: ${ $context.FetchPricing.discount }
      then: NextTask
```

This is especially useful when task outputs might overwrite data with the same names.

### Accessing Data from Complex Task Types

Some task types, like `fork` or `for`, produce structured outputs:

```yaml
  - ParallelProcessing:
      fork:
        branches:
          - UserBranch:
              do:
                - GetUserData:
                    call: userService
                    with:
                      userId: ${ .userId }
          - OrderBranch:
              do:
                - GetOrderHistory:
                    call: orderService
                    with:
                      userId: ${ .userId }
      then: CombineResults
  - CombineResults:
      set:
        userData: ${ $context.UserBranch.result }
        orderHistory: ${ $context.OrderBranch.result }
        dashboard:
          user:
            name: ${ $context.UserBranch.result.name }
            email: ${ $context.UserBranch.result.email }
          recentOrders: ${ $context.OrderBranch.result.orders[0:3] }
      then: NextTask
```

For loops, access array results:

```yaml
  - ProcessItems:
      for:
        each: "item"
        in: ${ .items }
      do:
        - ProcessItem:
            call: itemProcessor
            with:
              itemId: ${ .item.id }
      then: SummarizeResults
  - SummarizeResults:
      set:
        processedItems: ${ $context.ProcessItems.results }
        itemCount: ${ $context.ProcessItems.results | length }
        successCount: ${ $context.ProcessItems.results | map(select(.status == "SUCCESS")) | length }
      then: NextTask
```

## Working with Input Data

### Workflow Input

When you start a workflow, you can provide input data. This input is accessible as `$WORKFLOW.input`:

```yaml
  - ProcessWorkflowInput:
      set:
        requestId: ${ $workflow.input.requestId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        validInput: ${ $workflow.input.requestId != null and $workflow.input.customerId != null }
      then: ValidateInput
```

### Default Values and Nulls

You can provide default values when data might be missing:

```yaml
  - SetDefaults:
      set:
        username: ${ .username // "Guest" }
        quantity: ${ .quantity // 1 }
        discount: ${ .discount // 0 }
      then: NextTask
```

### Conditional Data Access

Use conditional expressions to access data safely:

```yaml
  - ProcessConditionalData:
      set:
        hasAddress: ${ .customer.address != null }
        shippingAddress: ${ if .hasAddress then .customer.address else .defaultAddress end }
        needsShipping: ${ .items | map(select(.digital == false)) | length > 0 }
      then: NextTask
```

## Transforming Data with JQ Expressions

Lemline uses JQ for data transformations. Here are common transformation patterns:

### String Manipulation

```yaml
  - StringTransformations:
      set:
        name: ${ .customer.name }
        uppercaseName: ${ .name | ascii_upcase }
        greeting: ${ "Hello, " + .name + "!" }
        truncatedDesc: ${ .product.description[0:50] + "..." }
        slug: ${ .name | ascii_downcase | gsub("\s+"; "-") }
      then: NextTask
```

### Numeric Operations

```yaml
  - NumericTransformations:
      set:
        price: ${ .product.price }
        quantity: ${ .order.quantity }
        subtotal: ${ .price * .quantity }
        tax: ${ .subtotal * 0.08 }
        total: ${ .subtotal + .tax }
        roundedTotal: ${ .total | round }
        formattedPrice: ${ "$" + (.price | tostring) }
      then: NextTask
```

### Array Operations

```yaml
  - ArrayTransformations:
      set:
        items: ${ .order.items }
        itemCount: ${ .items | length }
        itemIds: ${ .items | map(.id) }
        subtotals: ${ .items | map(.price * .quantity) }
        totalAmount: ${ .subtotals | add }
        hasExpensiveItems: ${ .items | map(.price) | max > 100 }
        expensive: ${ .items | map(select(.price > 100)) }
        sortedItems: ${ .items | sort_by(.price) }
      then: NextTask
```

### Object Transformations

```yaml
  - ObjectTransformations:
      set:
        customer: ${ .order.customer }
        enrichedCustomer: '${ .customer + { fullName: .customer.firstName + " " + .customer.lastName, isVIP: .customer.totalOrders
          > 10 } }'
        orderSummary:
          id: ${ .order.id }
          customer: ${ .customer.fullName }
          total: ${ .order.total }
          date: ${ .order.date }
      then: NextTask
```

### Filtering and Selecting

```yaml
  - FilteringData:
      set:
        allItems: ${ .inventory.items }
        inStockItems: ${ .allItems | map(select(.stock > 0)) }
        lowStockItems: ${ .allItems | map(select(.stock > 0 and .stock < 10)) }
        outOfStockItems: ${ .allItems | map(select(.stock == 0)) }
        categoryItems: ${ .allItems | map(select(.category == "electronics")) }
      then: NextTask
```

### Grouping and Aggregation

```yaml
  - GroupingData:
      set:
        orders: ${ .customerOrders }
        ordersByStatus: ${ .orders | group_by(.status) }
        statusCounts: '${ .orders | group_by(.status) | map({status: .[0].status, count: length}) }'
        totalByMonth: '${ .orders | group_by(.month) | map({month: .[0].month, total: map(.amount) | add}) }'
      then: NextTask
```

## Exporting Data to Global Context

The `export` property allows you to save data to the global workflow context, making it available to all subsequent tasks:

```yaml
  - ProcessOrderData:
      call: orderService
      with:
        orderId: ${ .orderId }
      then: ProcessShipping
      export:
        as:
          order: ${ . }
          customer: ${ .customer }
          items: ${ .items }
```

Exported data is available via `$WORKFLOW.order`, `$WORKFLOW.customer`, etc.

## Validating Data with Schemas

You can validate data against schemas when passing it between tasks:

### Input Validation

```yaml
  - ValidatePaymentData:
      call: paymentService
      with:
        schema:
          type: object
          required:
            - "amount"
            - "currency"
            - "paymentMethod"
          properties:
            amount:
              type: number
              minimum: 0.01
            currency:
              type: string
              enum:
                - "USD"
                - "EUR"
                - "GBP"
            paymentMethod:
              type: object
              required:
                - "type"
                - "token"
        order:
          amount: ${ .order.total }
          currency: ${ .order.currency }
          paymentMethod:
            type: ${ .payment.type }
            token: ${ .payment.token }
      then: ProcessPaymentResult
```

### Output Validation

```yaml
  - ProcessCustomerData:
      set:
        schema:
          type: object
          required:
            - "id"
            - "name"
            - "email"
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
          id: ${ .userId }
          name: ${ .fullName }
          email: ${ .emailAddress }
      then: NextTask
```

## Real-World Example: Order Processing Data Flow

Here's a complete example of data passing through an order processing workflow:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing Workflow
do:
  - ReceiveOrderData:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        metadata: ${ $workflow.input.metadata // {} }
        createdAt: ${ $workflow.startedAt }
      then: ValidateOrder
  - ValidateOrder:
      set:
        hasItems: ${ .items | length > 0 }
        hasCustomer: ${ .customerId != null }
        isValid: ${ .hasItems and .hasCustomer }
        validationMessage: '${ if .isValid then "Order validated successfully" else "Invalid order: " + (if .hasItems == false
          then "No items" else "" end) + (if .hasCustomer == false then "No customer" else "" end) end }'
      then: CheckValidOrder
  - CheckValidOrder:
      switch:
        - case1:
            then: RejectOrder
            when: ${ .isValid == false }
        - default:
            then: EnrichOrderData
  - EnrichOrderData:
      fork:
        branches:
          - CustomerBranch:
              do:
                - GetCustomerData:
                    call: customerService
                    with:
                      customerId: ${ .customerId }
                      include: "profile,addresses,payment_methods"
          - ProductBranch:
              do:
                - GetProductDetails:
                    call: productService
                    with:
                      productIds: ${ .items | map(.productId) }
      then: CalculateOrderTotals
  - CalculateOrderTotals:
      set:
        customer: ${ $context.CustomerBranch.result }
        products: ${ $context.ProductBranch.result.products }
        enrichedItems: '${ .items | map(. as $item | ($context.ProductBranch.result.products | map(select(.id == $item.productId))
          | first) as $product | . + {product: $product, subtotal: (.quantity * $product.price)}) }'
        subtotal: ${ .enrichedItems | map(.subtotal) | add }
        taxRate: ${ if .customer.taxExempt then 0 else 0.08 end }
        tax: ${ .subtotal * .taxRate }
        hasLoyaltyDiscount: ${ .customer.loyaltyTier == "GOLD" // .customer.loyaltyTier == "PLATINUM" }
        loyaltyDiscountRate: ${ if .customer.loyaltyTier == "PLATINUM" then 0.10 else if .customer.loyaltyTier == "GOLD"
          then 0.05 else 0 end end }
        loyaltyDiscount: ${ .subtotal * .loyaltyDiscountRate }
        total: ${ .subtotal + .tax - .loyaltyDiscount }
        formattedSubtotal: ${ "$" + (.subtotal | tostring) }
        formattedTax: ${ "$" + (.tax | tostring) }
        formattedDiscount: ${ "$" + (.loyaltyDiscount | tostring) }
        formattedTotal: ${ "$" + (.total | tostring) }
        summary:
          orderId: ${ .orderId }
          customer:
            id: ${ .customer.id }
            name: ${ .customer.fullName }
            email: ${ .customer.email }
            loyaltyTier: ${ .customer.loyaltyTier }
          items: '${ .enrichedItems | map({ productId: .productId, productName: .product.name, quantity: .quantity, unitPrice:
            .product.price, subtotal: .subtotal }) }'
          pricing:
            subtotal: ${ .subtotal }
            tax: ${ .tax }
            discount: ${ .loyaltyDiscount }
            total: ${ .total }
          shipping:
            address: ${ .customer.defaultShippingAddress }
            method: "STANDARD"
          createdAt: ${ .createdAt }
      then: ProcessPayment
      export:
        as:
          orderSummary: ${ .summary }
  - ProcessPayment:
      call: paymentService
      with:
        customerId: ${ .customer.id }
        paymentMethodId: ${ .customer.defaultPaymentMethod.id }
        amount: ${ .total }
        currency: "USD"
        description: ${ "Order \(.orderId)" }
      then: EvaluatePaymentResult
  - EvaluatePaymentResult:
      switch:
        - case1:
            then: CreateShipment
            when: ${ .status == "succeeded" }
        - default:
            then: HandlePaymentFailure
  - CreateShipment:
      call: shippingService
      with:
        orderId: ${ .orderId }
        customerId: ${ .customer.id }
        items: '${ .enrichedItems | map({ productId: .productId, quantity: .quantity, weight: .product.weight, dimensions:
          .product.dimensions }) }'
        shippingAddress: ${ .customer.defaultShippingAddress }
        shippingMethod: "STANDARD"
      then: FinalizeOrder
  - FinalizeOrder:
      set:
        finalOrder:
          orderId: ${ .orderId }
          status: "CONFIRMED"
          customer: ${ .customer.fullName }
          items: ${ .enrichedItems | length }
          total: ${ .formattedTotal }
          paymentId: ${ .transactionId }
          shipmentId: ${ .shipmentId }
          trackingNumber: ${ .trackingNumber }
          estimatedDelivery: ${ .estimatedDelivery }
        confirmation: '${ "Order \(.orderId) confirmed for \(.customer.fullName). Total: \(.formattedTotal). Tracking: \(.trackingNumber)"
          }'
      then: SendOrderConfirmation
  - SendOrderConfirmation:
      call: notificationService
      with:
        recipient: ${ .customer.email }
        subject: '${ "Order Confirmation: \(.orderId)" }'
        message: ${ .confirmation }
        details: ${ .finalOrder }
      then: end
  - RejectOrder:
      set:
        status: "REJECTED"
        reason: ${ .validationMessage }
      then: end
  - HandlePaymentFailure:
      set:
        status: "PAYMENT_FAILED"
        errorMessage: ${ .errorMessage // "Payment processing failed" }
        declineReason: ${ .declineReason // "Unknown error" }
      then: end
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