---
title: Flow Tasks
---

# Flow Tasks

Flow tasks are fundamental components that control the execution path of your serverless workflows. They determine the order in which tasks run, manage conditional branching, handle iterations, and orchestrate parallel execution paths.

## Flow Task Types

| Task | Purpose |
|------|---------|
| [Do](dsl-task-do.md) | Define sequential execution of one or more tasks |
| [For](dsl-task-for.md) | Iterate over a collection of items |
| [Switch](dsl-task-switch.md) | Implement conditional branching based on data values |
| [Fork](dsl-task-fork.md) | Execute multiple branches in parallel |

## When to Use Flow Tasks

### Sequential Execution with Do

Use the **Do** task when you need to:
- Define a sequence of operations that should run in a specific order
- Group related tasks together for better organization
- Create nested execution blocks for complex workflows
- Implement a series of steps that build on each other

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: order-processing
  version: '1.0.0'
do:
  - validateOrder:
      call: function
      with:
        function: orderValidator
        args:
          order: ${ .input.order }
      result: validationResult
      
  - processPayment:
      call: function
      with:
        function: paymentProcessor
        args:
          amount: ${ .input.order.totalAmount }
          paymentDetails: ${ .input.paymentInfo }
      result: paymentResult
      
  - updateInventory:
      call: function
      with:
        function: inventoryManager
        args:
          items: ${ .input.order.items }
          operation: "reserve"
      result: inventoryResult
```

### Iterative Processing with For

Use the **For** task when you need to:
- Process each item in a collection or array
- Execute the same operation multiple times with different inputs
- Break down bulk operations into individual item processing
- Create data transformation pipelines that operate on collections

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: process-order-items
  version: '1.0.0'
do:
  - processItems:
      for:
        from: ${ .input.order.items }
        as: currentItem
        do:
          - updateInventory:
              call: function
              with:
                function: inventoryUpdater
                args:
                  productId: ${ .currentItem.productId }
                  quantity: ${ .currentItem.quantity }
                  operation: "decrease"
              result: updateResult
              
          - recordItemFulfillment:
              call: function
              with:
                function: orderItemTracker
                args:
                  orderId: ${ .input.order.id }
                  itemId: ${ .currentItem.id }
                  status: "fulfilled"
              result: trackingResult
```

### Conditional Logic with Switch

Use the **Switch** task when you need to:
- Implement decision-making logic based on data values
- Create different execution paths based on conditions
- Handle multiple possible values with distinct processing for each
- Implement business rules with clear branching logic

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: payment-processor
  version: '1.0.0'
do:
  - determinePaymentMethod:
      switch:
        on: ${ .input.paymentMethod }
        cases:
          - value: "creditCard"
            do:
              - processCreditCard:
                  call: function
                  with:
                    function: creditCardProcessor
                    args:
                      cardDetails: ${ .input.paymentDetails }
                      amount: ${ .input.amount }
                  result: paymentResult
                  
          - value: "paypal"
            do:
              - processPayPal:
                  call: function
                  with:
                    function: paypalProcessor
                    args:
                      paypalAccount: ${ .input.paymentDetails }
                      amount: ${ .input.amount }
                  result: paymentResult
                  
          - value: "bankTransfer"
            do:
              - processBankTransfer:
                  call: function
                  with:
                    function: bankTransferProcessor
                    args:
                      bankDetails: ${ .input.paymentDetails }
                      amount: ${ .input.amount }
                  result: paymentResult
        
        default:
          do:
            - handleUnsupportedPaymentMethod:
                set:
                  result:
                    success: false
                    errorCode: "UNSUPPORTED_PAYMENT_METHOD"
                    message: ${ "Payment method " + .input.paymentMethod + " is not supported" }
```

### Parallel Execution with Fork

Use the **Fork** task when you need to:
- Execute multiple independent tasks simultaneously
- Improve workflow performance by parallelizing operations
- Process multiple streams of data concurrently
- Implement fan-out patterns for workload distribution

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: product-data-enrichment
  version: '1.0.0'
do:
  - enrichProductData:
      fork:
        - branch: fetchPricing
          do:
            - getPricingData:
                call: function
                with:
                  function: pricingService
                  args:
                    productId: ${ .input.productId }
                result: pricingData
        
        - branch: fetchInventory
          do:
            - getInventoryStatus:
                call: function
                with:
                  function: inventoryService
                  args:
                    productId: ${ .input.productId }
                result: inventoryData
        
        - branch: fetchReviews
          do:
            - getProductReviews:
                call: function
                with:
                  function: reviewService
                  args:
                    productId: ${ .input.productId }
                result: reviewData
      
      join: all
      result: enrichedData
```

## Combining Flow Tasks

Flow tasks are often combined to create sophisticated workflows that accurately reflect complex business processes:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: order-fulfillment-workflow
  version: '1.0.0'
do:
  - validateOrder:
      call: function
      with:
        function: orderValidator
        args:
          order: ${ .input.order }
      result: validationResult
      
  - checkOrderValidity:
      switch:
        on: ${ .validationResult.valid }
        cases:
          - value: false
            do:
              - handleInvalidOrder:
                  set:
                    result:
                      success: false
                      message: ${ .validationResult.reason }
                      code: "INVALID_ORDER"
                  try:
                    do:
                      - notifyCustomer:
                          call: function
                          with:
                            function: emailService
                            args:
                              to: ${ .input.customer.email }
                              subject: "Order Validation Failed"
                              message: ${ .validationResult.reason }
                    catch:
                      do:
                        - logEmailFailure:
                            call: function
                            with:
                              function: logger
                              args:
                                level: "WARNING"
                                message: "Failed to send validation failure email"
                                orderId: ${ .input.order.id }
  
  - processValidOrder:
      fork:
        - branch: handlePayment
          do:
            - processPayment:
                call: function
                with:
                  function: paymentProcessor
                  args:
                    amount: ${ .input.order.totalAmount }
                    currency: ${ .input.order.currency }
                    paymentMethod: ${ .input.paymentInfo }
                result: paymentResult
        
        - branch: reserveInventory
          do:
            - checkInventoryForItems:
                for:
                  from: ${ .input.order.items }
                  as: item
                  do:
                    - reserveItem:
                        call: function
                        with:
                          function: inventoryManager
                          args:
                            productId: ${ .item.productId }
                            quantity: ${ .item.quantity }
                            operation: "reserve"
                        result: itemReservation
                
                result: inventoryReservations
      
      join: all
      result: processingResults
      
  - prepareOrderResponse:
      set:
        result:
          orderId: ${ .input.order.id }
          status: "PROCESSING"
          paymentStatus: ${ .processingResults.branches.handlePayment.paymentResult.status }
          inventoryStatus: ${ .processingResults.branches.reserveInventory.inventoryReservations }
          estimatedShipDate: ${ new Date(Date.now() + 86400000 * 2).toISOString() }
```

Flow tasks are the building blocks of workflow orchestration, enabling you to create maintainable workflows that reflect your business processes while separating orchestration logic from the implementation of business logic. 