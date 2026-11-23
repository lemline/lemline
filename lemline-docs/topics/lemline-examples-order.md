---
title: Order Processing Workflow Example
---

# Order Processing Workflow Example

This example demonstrates a complete order processing workflow that handles validation, payment processing, inventory management, shipping, and notifications. It showcases many of Lemline's features including error handling, event correlation, and data transformation.

## Use Case Overview

This workflow implements an e-commerce order processing pipeline that:

1. Receives an order request
2. Validates customer and order details
3. Checks inventory availability
4. Processes payment
5. Allocates inventory
6. Creates a shipment
7. Waits for shipping confirmation
8. Sends order confirmations and updates
9. Handles potential failures at each step

## Full Workflow Definition

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing Workflow
do:
  - ReceiveOrder:
      set:
        orderId: ${ $workflow.input.orderId }
        customerId: ${ $workflow.input.customerId }
        items: ${ $workflow.input.items }
        shippingAddress: ${ $workflow.input.shippingAddress }
        paymentMethod: ${ $workflow.input.paymentMethod }
        timestamp: ${ $workflow.startedAt }
        orderStatus: "RECEIVED"
      then: ValidateOrder
  - ValidateOrder:
      set:
        isValid: ${ .items and .items | length > 0 and .customerId and .shippingAddress and .paymentMethod }
        validationMessage: '${ if .isValid then "Order validated" else "Invalid order: Missing required fields" end }'
      then: CheckOrderValidity
  - CheckOrderValidity:
      switch:
        - case1:
            then: RejectOrder
            when: ${ .isValid == false }
        - default:
            then: ValidateCustomer
  - ValidateCustomer:
      try:
        - CheckCustomer:
            call: http
            with:
              method: get
              endpoint: https://api.example.com/customers/{customerId}/validate
              query:
                customerId: ${ .customerId }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandleInvalidCustomer
                    when: ${ $failure.status == 404 }
                - case2:
                    then: HandleValidationError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: CheckInventory
  - CheckInventory:
      try:
        - VerifyInventory:
            call: http
            with:
              method: post
              endpoint: https://api.example.com/inventory/check
              body:
                items: '${ .items | map({productId: .productId, quantity: .quantity}) }'
      catch:
        as: failure
        then: HandleInventoryError
        retry:
          limit:
            attempt:
              count: 2
      then: EvaluateInventory
  - EvaluateInventory:
      set:
        inventoryResult: ${ . }
        allAvailable: ${ .available == true }
        partiallyAvailable: ${ .partiallyAvailable == true }
        unavailableItems: ${ .unavailableItems // [] }
        availableItems: ${ .availableItems // .items }
        orderTotal: ${ .totalPrice }
      then: CheckInventoryResult
  - CheckInventoryResult:
      switch:
        - case1:
            then: ProcessPayment
            when: ${ .allAvailable == true }
        - case2:
            then: AdjustForPartialInventory
            when: ${ .partiallyAvailable == true }
        - default:
            then: HandleUnavailableInventory
  - AdjustForPartialInventory:
      set:
        originalItems: ${ .items }
        items: ${ .availableItems }
        unavailableItems: ${ .unavailableItems }
        adjustmentReason: "Some items are unavailable"
        orderTotal: ${ .adjustedTotalPrice }
        orderStatus: "ADJUSTED"
      then: NotifyAdjustment
  - NotifyAdjustment:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "ORDER_ADJUSTED"
          subject: "Your order has been adjusted"
          message: "Some items in your order were unavailable and have been removed."
          details:
            unavailableItems: ${ .unavailableItems | map(.productId) }
            adjustedTotal: ${ .orderTotal }
      then: ProcessPayment
  - ProcessPayment:
      try:
        - ChargePayment:
            call: http
            with:
              method: post
              endpoint: https://api.example.com/payments/process
              body:
                customerId: ${ .customerId }
                orderId: ${ .orderId }
                amount: ${ .orderTotal }
                currency: "USD"
                paymentMethod: ${ .paymentMethod }
      catch:
        as: failure
        do:
          - routeError:
              switch:
                - case1:
                    then: HandlePaymentRejected
                    when: ${ $failure.status == 402 }
                - case2:
                    then: HandlePaymentError
        retry:
          limit:
            attempt:
              count: 2
      then: CheckPaymentResult
  - CheckPaymentResult:
      switch:
        - case1:
            then: AllocateInventory
            when: ${ .status == "APPROVED" }
        - default:
            then: HandlePaymentRejection
  - AllocateInventory:
      try:
        - ReserveInventory:
            call: http
            with:
              method: post
              endpoint: https://api.example.com/inventory/allocate
              body:
                orderId: ${ .orderId }
                items: '${ .items | map({productId: .productId, quantity: .quantity}) }'
      catch:
        as: failure
        then: RollbackPayment
        retry:
          limit:
            attempt:
              count: 3
      then: CreateShipment
  - CreateShipment:
      try:
        - ArrangeShipment:
            call: http
            with:
              method: post
              endpoint: https://api.example.com/shipments
              body:
                orderId: ${ .orderId }
                customerId: ${ .customerId }
                items: ${ .items }
                shippingAddress: ${ .shippingAddress }
      catch:
        as: failure
        then: RollbackAllocation
        retry:
          limit:
            attempt:
              count: 3
      then: WaitForShipmentUpdate
  - WaitForShipmentUpdate:
      try:
        - WaitForShipmentUpdateEvents:
            listen:
              to:
                one:
                  with:
                    type: "ShipmentStatusUpdate"
                  correlate:
                    orderId:
                      from: ${ .data.orderId }
                      expect: ${ .orderId }
            timeout:
              after: PT24H
      catch:
        errors:
          with:
            status: 408
        then: HandleShipmentTimeout
      then: ProcessShipmentUpdate
  - ProcessShipmentUpdate:
      set:
        shipmentEvent: ${ .events.ShipmentUpdates[0] }
        shipmentStatus: ${ .shipmentEvent.status }
        trackingNumber: ${ .shipmentEvent.trackingNumber }
        estimatedDelivery: ${ .shipmentEvent.estimatedDelivery }
        carrierName: ${ .shipmentEvent.carrier }
        orderStatus: "SHIPPED"
      then: SendOrderConfirmation
  - SendOrderConfirmation:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "ORDER_SHIPPED"
          subject: "Your order has shipped!"
          message: "Your order has been shipped and is on its way."
          details:
            orderId: ${ .orderId }
            status: ${ .orderStatus }
            trackingNumber: ${ .trackingNumber }
            carrier: ${ .carrierName }
            estimatedDelivery: ${ .estimatedDelivery }
            items: '${ .items | map({productId: .productId, quantity: .quantity}) }'
      then: CompleteOrder
  - CompleteOrder:
      set:
        completedAt: ${ now | todateiso8601 }
        orderStatus: "COMPLETED"
        processingDuration: ${ now - $workflow.startedAt.epoch.seconds | floor }
        summary:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          status: ${ .orderStatus }
          items: ${ .items | length }
          total: ${ .orderTotal }
          paymentId: ${ .transactionId }
          trackingNumber: ${ .trackingNumber }
          estimatedDelivery: ${ .estimatedDelivery }
      then: EmitOrderCompleted
  - EmitOrderCompleted:
      emit:
        event:
          with:
            type: "OrderCompleted"
            data:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              status: ${ .orderStatus }
              completedAt: ${ .completedAt }
              summary: ${ .summary }
      then: end
  - RejectOrder:
      set:
        orderStatus: "REJECTED"
        rejectionReason: ${ .validationMessage }
      then: NotifyRejection
  - NotifyRejection:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "ORDER_REJECTED"
          subject: "Your order could not be processed"
          message: ${ .rejectionReason }
      then: FinalizeRejection
  - FinalizeRejection:
      set:
        completedAt: ${ now | todateiso8601 }
        summary:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          status: ${ .orderStatus }
          reason: ${ .rejectionReason }
      then: EmitOrderRejected
  - EmitOrderRejected:
      emit:
        event:
          with:
            type: "OrderRejected"
            data:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              status: ${ .orderStatus }
              reason: ${ .rejectionReason }
              completedAt: ${ .completedAt }
      then: end
  - HandleInvalidCustomer:
      set:
        orderStatus: "REJECTED"
        rejectionReason: "Invalid customer account"
      then: NotifyRejection
  - HandleValidationError:
      set:
        orderStatus: "ERROR"
        errorType: "VALIDATION_ERROR"
        errorMessage: "Customer validation failed"
        errorDetails: ${ $context.error }
      then: NotifyError
  - HandleInventoryError:
      set:
        orderStatus: "ERROR"
        errorType: "INVENTORY_ERROR"
        errorMessage: "Failed to check inventory availability"
        errorDetails: ${ $context.error }
      then: NotifyError
  - HandleUnavailableInventory:
      set:
        orderStatus: "REJECTED"
        rejectionReason: "Items are unavailable"
        unavailableItems: ${ .unavailableItems }
      then: NotifyUnavailableItems
  - NotifyUnavailableItems:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "ITEMS_UNAVAILABLE"
          subject: "Items in your order are unavailable"
          message: "Unfortunately, some items in your order are currently unavailable."
          details:
            orderId: ${ .orderId }
            unavailableItems: ${ .unavailableItems | map(.productId) }
      then: FinalizeRejection
  - HandlePaymentRejected:
      set:
        orderStatus: "PAYMENT_FAILED"
        rejectionReason: "Payment was declined"
        declineReason: ${ .declineReason // "Unknown decline reason" }
      then: NotifyPaymentDeclined
  - NotifyPaymentDeclined:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "PAYMENT_DECLINED"
          subject: "Your payment was declined"
          message: '${ "The payment for your order was declined: \(.declineReason)" }'
      then: FinalizeRejection
  - HandlePaymentError:
      set:
        orderStatus: "ERROR"
        errorType: "PAYMENT_ERROR"
        errorMessage: "Payment processing failed"
        errorDetails: ${ $context.error }
      then: NotifyError
  - HandlePaymentRejection:
      set:
        orderStatus: "PAYMENT_FAILED"
        rejectionReason: '${ "Payment status: \(.status)" }'
      then: NotifyPaymentDeclined
  - RollbackPayment:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/payments/process
        body:
          operation: "REFUND"
          customerId: ${ .customerId }
          orderId: ${ .orderId }
          paymentId: ${ .transactionId }
          amount: ${ .orderTotal }
          reason: "Inventory allocation failed"
      then: HandleAllocationError
  - HandleAllocationError:
      set:
        orderStatus: "ERROR"
        errorType: "ALLOCATION_ERROR"
        errorMessage: "Failed to allocate inventory"
        errorDetails: ${ $context.error }
      then: NotifyError
  - RollbackAllocation:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/inventory/allocate
        body:
          operation: "RELEASE"
          orderId: ${ .orderId }
          items: '${ .items | map({productId: .productId, quantity: .quantity}) }'
      then: RollbackPayment
  - HandleShipmentTimeout:
      set:
        orderStatus: "PROCESSING"
        message: "Shipment update not received within expected timeframe"
      then: CheckShipmentStatus
  - CheckShipmentStatus:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/shipments
        body:
          operation: "STATUS"
          shipmentId: ${ .shipmentId }
      then: ProcessManualShipmentCheck
  - ProcessManualShipmentCheck:
      set:
        shipmentStatus: ${ .status }
        trackingNumber: ${ .trackingNumber // "Not available" }
        estimatedDelivery: ${ .estimatedDelivery // "Unknown" }
        carrierName: ${ .carrier // "Unknown" }
      then: SendShipmentFollowup
  - SendShipmentFollowup:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "SHIPMENT_UPDATE"
          subject: "Your order is on its way"
          message: "Your order is currently being processed by our shipping department."
          details:
            orderId: ${ .orderId }
            status: ${ .shipmentStatus }
            trackingNumber: ${ .trackingNumber }
      then: CompleteOrder
  - NotifyError:
      call: http
      with:
        method: post
        endpoint: https://api.example.com/notifications/send
        body:
          to: ${ .customerId }
          type: "ORDER_ERROR"
          subject: "There was a problem with your order"
          message: ${ .errorMessage }
          details:
            orderId: ${ .orderId }
            status: ${ .orderStatus }
            errorType: ${ .errorType }
      then: FinalizeError
  - FinalizeError:
      set:
        completedAt: ${ now | todateiso8601 }
        summary:
          orderId: ${ .orderId }
          customerId: ${ .customerId }
          status: ${ .orderStatus }
          errorType: ${ .errorType }
          errorMessage: ${ .errorMessage }
      then: EmitOrderError
  - EmitOrderError:
      emit:
        event:
          with:
            type: "OrderError"
            data:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              status: ${ .orderStatus }
              errorType: ${ .errorType }
              errorMessage: ${ .errorMessage }
              completedAt: ${ .completedAt }
      then: end
```

## Key Features Demonstrated

### 1. Workflow Organization

This workflow demonstrates good organization practices:
- Clear task naming that describes the purpose
- Logical flow grouping (validation, inventory, payment, shipping)
- Comprehensive error handling for each major section
- Proper event emission for important state changes

### 2. Error Handling

The workflow implements a robust error handling strategy:
- Specific error handlers for different error types
- Retry policies for transient failures
- Rollback operations for partial failures (payment, inventory)
- Proper customer notifications for different error scenarios

### 3. Event Correlation

The workflow uses event correlation to integrate with external systems:
- Waits for shipment updates correlated by order ID
- Handles timeout with a fallback path to check status manually
- Emits events at key lifecycle points

### 4. Data Transformations

Various data transformation patterns are demonstrated:
- Building structured notification messages
- Calculating processing durations
- Constructing summary objects
- Mapping and filtering array data

### 5. Conditional Processing

The workflow includes multiple decision points:
- Validating input conditions
- Checking inventory availability
- Evaluating payment results
- Processing shipment status

### 6. Compensation Logic

For handling partial failures, the workflow includes compensation logic:
- Refunding payments if inventory allocation fails
- Releasing allocated inventory if shipment creation fails
- Adjusting orders for partial inventory availability

## How to Use This Example

You can use this example as a starting point for your own order processing workflows. To adapt it to your environment:

1. Replace the function URLs with your actual service endpoints
2. Adjust the data structures to match your specific schema
3. Modify the error handling to align with your business processes
4. Customize the notification messages to match your brand voice
5. Add or remove steps based on your specific requirements

## Testing This Workflow

To test this workflow locally, with a runner (`lemline listen`) running:

```bash
# Register the definition
lemline definition post -f order-processing.yaml

# Start the workflow with a test order
lemline instance start <namespace> <name> --input "$(cat orders/test-order.json)"
```

Replace `<namespace>` and `<name>` with the values declared in the `document` section. To simulate the shipment status update, publish a `ShipmentStatusUpdate` CloudEvent on the CloudEvents topic or queue of that namespace with your broker's tools; Lemline has no CLI command to publish events.

To run it without calling the real services, start the runner with `lemline listen --mock-config <file>`.

## Related Resources

To learn more about the concepts demonstrated in this example:

- [How to define a workflow](lemline-howto-define-workflow.md)
- [How to make HTTP calls](lemline-howto-http.md)
- [How to emit and listen for events](lemline-howto-events.md)
- [How to use try/catch to handle faults](lemline-howto-try-catch.md)
- [Data passing between tasks](lemline-howto-data-passing.md)

## Performance Considerations

This workflow is designed for reliability and correctness. For high-volume scenarios, consider these optimizations:

1. **Parallel Processing**: Use the `fork` task to parallelize independent operations
2. **Batched Operations**: Group related items for inventory or shipping operations
3. **Event Streaming**: Use streaming responses for higher throughput
5. **Response Caching**: Cache customer or product data that doesn't change frequently