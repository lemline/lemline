---
title: Control Flow
---

# Flow Tasks

Flow tasks are fundamental components that control the execution path of your open workflows. They determine the
order in which tasks run, manage conditional branching, handle iterations, and orchestrate parallel execution paths.

## Flow Task Types

| Task                         | Purpose                                              |
|------------------------------|------------------------------------------------------|
| [Do](dsl-task-do.md)         | Define sequential execution of one or more tasks     |
| [For](dsl-task-for.md)       | Iterate over a collection of items                   |
| [Switch](dsl-task-switch.md) | Implement conditional branching based on data values |
| [Fork](dsl-task-fork.md)     | Execute multiple branches in parallel                |

## When to Use Flow Tasks

### Sequential Execution with Do

Use the **Do** task when you need to:

- Define a sequence of operations that should run in a specific order
- Group related tasks together for better organization
- Create nested execution blocks for complex workflows
- Implement a series of steps that build on each other

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: order-processing
  version: '1.0.0'
use:
  functions:
    orderValidator:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/orderValidator
        body: ${ . }
      ## define function here...
    paymentProcessor:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/paymentProcessor
        body: ${ . }
      ## define function here...
    inventoryManager:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/inventoryManager
        body: ${ . }
      ## define function here...
do:
  - validateOrder:
      call: orderValidator
      with:
        order: ${ .input.order }

  - processPayment:
      call: paymentProcessor
      with:
        amount: ${ .input.order.totalAmount }
        paymentDetails: ${ .input.paymentInfo }

  - updateInventory:
      call: inventoryManager
      with:
        items: ${ .input.order.items }
        operation: "reserve"
```

### Iterative Processing with For

Use the **For** task when you need to:

- Process each item in a collection or array
- Execute the same operation multiple times with different inputs
- Break down bulk operations into individual item processing
- Create data transformation pipelines that operate on collections

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: process-order-items
  version: '1.0.0'
use:
  functions:
    inventoryUpdater:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/inventoryUpdater
        body: ${ . }
      ## define function here...
    orderItemTracker:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/orderItemTracker
        body: ${ . }
      ## define function here...
do:
  - processItems:
      for:
        in: ${ .input.order.items }
        each: currentItem
      do:
        - updateInventory:
            call: inventoryUpdater
            with:
              args:
                productId: ${ .currentItem.productId }
                quantity: ${ .currentItem.quantity }
                operation: "DECREMENT"

        - recordItemFulfillment:
            call: orderItemTracker
            with:
              args:
                orderId: ${ .input.order.id }
                itemId: ${ .currentItem.id }
                status: "FULFILLED"
```

### Conditional Logic with Switch

Use the **Switch** task when you need to:

- Implement decision-making logic based on data values
- Create different execution paths based on conditions
- Handle multiple possible values with distinct processing for each
- Implement business rules with clear branching logic

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: payment-processor
  version: '1.0.0'
use:
  functions:
    creditCardProcessor:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/creditCardProcessor
        body: ${ . }
      ## define function here...
    paypalProcessor:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/paypalProcessor
        body: ${ . }
      ## define function here...
    bankTransferProcessor:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/bankTransferProcessor
        body: ${ . }
      ## define function here...
do:
  - determinePaymentMethod:
      switch:
        - creditCard:
            when: ${ .input.paymentMethod == "creditCard" }
            then: processCreditCard

        - paypal:
            when: ${ .input.paymentMethod == "paypal" }
            then: processPayPal

        - bankTransfer:
            when: ${ .input.paymentMethod == "bankTransfer" }
            then: processBankTransfer

        - default:
            then: handleUnsupportedPaymentMethod

  - processCreditCard:
      call: creditCardProcessor
      with:
        args:
          cardDetails: ${ .input.paymentDetails }
          amount: ${ .input.amount }
      then: exit

  - processPayPal:
      call: paypalProcessor
      with:
        args:
          paypalAccount: ${ .input.paymentDetails }
          amount: ${ .input.amount }
      then: exit

  - processBankTransfer:
      call: bankTransferProcessor
      with:
        args:
          bankDetails: ${ .input.paymentDetails }
          amount: ${ .input.amount }
      then: exit

  - handleUnsupportedPaymentMethod:
      set:
        result:
          success: false
          errorCode: "UNSUPPORTED_PAYMENT_METHOD"
          message: ${ "Payment method " + .input.paymentMethod + " is not supported" }
      then: exit

```

### Parallel Execution with Fork

Use the **Fork** task when you need to:

- Execute multiple independent tasks simultaneously
- Improve workflow performance by parallelizing operations
- Process multiple streams of data concurrently
- Implement fan-out patterns for workload distribution

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: product-data-enrichment
  version: '1.0.0'
use:
  functions:
    pricingService:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/pricingService
        body: ${ . }
      ## define function here...
    inventoryService:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/inventoryService
        body: ${ . }
      ## define function here...
    reviewService:
      call: http
      with:
        method: post
        endpoint: https://services.example.com/reviewService
        body: ${ . }
      ## define function here...
do:
  - enrichProductData:
      fork:
        compete: false
        branches:
          - fetchPricing:
              call: pricingService
              with:
                args:
                  productId: ${ .input.productId }

          - fetchInventory:
              call: inventoryService
              with:
                args:
                  productId: ${ .input.productId }

          - fetchReviews:
              call: reviewService
              with:
                args:
                  productId: ${ .input.productId }
```

## Combining Flow Tasks

Flow tasks are often combined to create sophisticated workflows that accurately reflect complex business processes:

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: order-fulfillment-workflow
  version: '1.0.0'
do:
  - validateOrder:
      call: orderValidator
      with:
        order: ${ .input.order }
      output:
        as: '${ $input + { "validationResult": . } }'
  - checkOrderValidity:
      switch:
        - case1:
            when: ${ .validationResult.valid == false }
            then: checkOrderValidity-case1
  - checkOrderValidity-case1:
      do:
        - handleInvalidOrder:
            set:
              result:
                success: false
                message: ${ .validationResult.reason }
                code: "INVALID_ORDER"
        - notifyCustomer:
            try:
              - notifyCustomerOperation:
                  call: emailService
                  with:
                    to: ${ .input.customer.email }
                    subject: "Order Validation Failed"
                    message: ${ .validationResult.reason }
            catch:
              errors:
                with:
                  type: "email-service-error"
              do:
                - logEmailFailure:
                    call: logger
                    with:
                      level: "WARNING"
                      message: "Failed to send validation failure email"
                      orderId: ${ .input.order.id }

      then: checkOrderValidity-done
  - checkOrderValidity-done:
      set: ${ . }
  - processValidOrder:
      fork:
        compete: false
        branches:
          - handlePayment:
              call: paymentProcessor
              with:
                amount: ${ .input.order.totalAmount }
                currency: ${ .input.order.currency }
                paymentMethod: ${ .input.paymentInfo }
              output:
                as: '${ $input + { "paymentResult": . } }'
          - reserveInventory:
              for:
                in: ${ .input.order.items }
                each: item
              do:
                - reserveItem:
                    call: inventoryManager
                    with:
                      productId: ${ .item.productId }
                      quantity: ${ .item.quantity }
                      operation: "reserve"

  - prepareOrderResponse:
      set:
        result:
          orderId: ${ .input.order.id }
          status: "PROCESSING"
          paymentStatus: ${ .processingResults.handlePayment.paymentResult.status }
          inventoryStatus: ${ .processingResults.reserveInventory.inventoryReservations }
          estimatedShipDate: ${ (now + 172800) | todateiso8601 }
```

Flow tasks are the building blocks of workflow orchestration, 
enabling you to create maintainable workflows that reflect
your business processes while separating orchestration logic from the implementation of business logic. 