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
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ReceiveOrder
functions:
  - name: validateCustomer
    type: http
    operation: GET
    url: https://api.example.com/customers/{customerId}/validate
  
  - name: checkInventory
    type: http
    operation: POST
    url: https://api.example.com/inventory/check
  
  - name: processPayment
    type: http
    operation: POST
    url: https://api.example.com/payments/process
  
  - name: allocateInventory
    type: http
    operation: POST
    url: https://api.example.com/inventory/allocate
  
  - name: createShipment
    type: http
    operation: POST
    url: https://api.example.com/shipments
  
  - name: sendNotification
    type: http
    operation: POST
    url: https://api.example.com/notifications/send
tasks:
  - name: ReceiveOrder
    type: set
    data:
      orderId: "$WORKFLOW.input.orderId"
      customerId: "$WORKFLOW.input.customerId"
      items: "$WORKFLOW.input.items"
      shippingAddress: "$WORKFLOW.input.shippingAddress"
      paymentMethod: "$WORKFLOW.input.paymentMethod"
      timestamp: "$WORKFLOW.startTime"
      orderStatus: "RECEIVED"
    next: ValidateOrder
  
  - name: ValidateOrder
    type: set
    data:
      isValid: ".items && .items | length > 0 && .customerId && .shippingAddress && .paymentMethod"
      validationMessage: "if .isValid then \"Order validated\" else \"Invalid order: Missing required fields\" end"
    next: CheckOrderValidity
  
  - name: CheckOrderValidity
    type: switch
    conditions:
      - condition: ".isValid == false"
        next: RejectOrder
      - condition: true
        next: ValidateCustomer
  
  - name: ValidateCustomer
    type: try
    retry:
      maxAttempts: 3
      interval: PT2S
    catch:
      - error: "HTTP_ERROR"
        status: 404
        next: HandleInvalidCustomer
      - error: "*"
        next: HandleValidationError
    do:
      - name: CheckCustomer
        type: call
        function: validateCustomer
        data:
          customerId: ".customerId"
    next: CheckInventory
  
  - name: CheckInventory
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "*"
        next: HandleInventoryError
    do:
      - name: VerifyInventory
        type: call
        function: checkInventory
        data:
          body:
            items: ".items | map({productId: .productId, quantity: .quantity})"
    next: EvaluateInventory
  
  - name: EvaluateInventory
    type: set
    data:
      inventoryResult: "."
      allAvailable: ".available == true"
      partiallyAvailable: ".partiallyAvailable == true"
      unavailableItems: ".unavailableItems || []"
      availableItems: ".availableItems || .items"
      orderTotal: ".totalPrice"
    next: CheckInventoryResult
  
  - name: CheckInventoryResult
    type: switch
    conditions:
      - condition: ".allAvailable == true"
        next: ProcessPayment
      - condition: ".partiallyAvailable == true"
        next: AdjustForPartialInventory
      - condition: true
        next: HandleUnavailableInventory
  
  - name: AdjustForPartialInventory
    type: set
    data:
      originalItems: ".items"
      items: ".availableItems"
      unavailableItems: ".unavailableItems"
      adjustmentReason: "Some items are unavailable"
      orderTotal: ".adjustedTotalPrice"
      orderStatus: "ADJUSTED"
    next: NotifyAdjustment
  
  - name: NotifyAdjustment
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "ORDER_ADJUSTED"
        subject: "Your order has been adjusted"
        message: "Some items in your order were unavailable and have been removed."
        details:
          unavailableItems: ".unavailableItems | map(.productId)"
          adjustedTotal: ".orderTotal"
    next: ProcessPayment
  
  - name: ProcessPayment
    type: try
    retry:
      maxAttempts: 2
    catch:
      - error: "HTTP_ERROR"
        status: 402
        next: HandlePaymentRejected
      - error: "*"
        next: HandlePaymentError
    do:
      - name: ChargePayment
        type: call
        function: processPayment
        data:
          body:
            customerId: ".customerId"
            orderId: ".orderId"
            amount: ".orderTotal"
            currency: "USD"
            paymentMethod: ".paymentMethod"
    next: CheckPaymentResult
  
  - name: CheckPaymentResult
    type: switch
    conditions:
      - condition: ".status == 'APPROVED'"
        next: AllocateInventory
      - condition: true
        next: HandlePaymentRejection
  
  - name: AllocateInventory
    type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "*"
        next: RollbackPayment
    do:
      - name: ReserveInventory
        type: call
        function: allocateInventory
        data:
          body:
            orderId: ".orderId"
            items: ".items | map({productId: .productId, quantity: .quantity})"
    next: CreateShipment
  
  - name: CreateShipment
    type: try
    retry:
      maxAttempts: 3
    catch:
      - error: "*"
        next: RollbackAllocation
    do:
      - name: ArrangeShipment
        type: call
        function: createShipment
        data:
          body:
            orderId: ".orderId"
            customerId: ".customerId"
            items: ".items"
            shippingAddress: ".shippingAddress"
    next: WaitForShipmentUpdate
  
  - name: WaitForShipmentUpdate
    type: listen
    events:
      - name: ShipmentUpdates
        type: "ShipmentStatusUpdate"
        correlations:
          - orderId = .orderId
        consume: 1
        timeouts:
          eventTimeout: PT24H
          eventTimeoutNext: HandleShipmentTimeout
    next: ProcessShipmentUpdate
  
  - name: ProcessShipmentUpdate
    type: set
    data:
      shipmentEvent: ".events.ShipmentUpdates[0]"
      shipmentStatus: ".shipmentEvent.status"
      trackingNumber: ".shipmentEvent.trackingNumber"
      estimatedDelivery: ".shipmentEvent.estimatedDelivery"
      carrierName: ".shipmentEvent.carrier"
      orderStatus: "SHIPPED"
    next: SendOrderConfirmation
  
  - name: SendOrderConfirmation
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "ORDER_SHIPPED"
        subject: "Your order has shipped!"
        message: "Your order has been shipped and is on its way."
        details:
          orderId: ".orderId"
          status: ".orderStatus"
          trackingNumber: ".trackingNumber"
          carrier: ".carrierName"
          estimatedDelivery: ".estimatedDelivery"
          items: ".items | map({productId: .productId, quantity: .quantity})"
    next: CompleteOrder
  
  - name: CompleteOrder
    type: set
    data:
      completedAt: "$WORKFLOW.currentTime"
      orderStatus: "COMPLETED"
      processingDuration: "date.difference(.timestamp, $WORKFLOW.currentTime, 'seconds')"
      summary: {
        orderId: ".orderId",
        customerId: ".customerId",
        status: ".orderStatus",
        items: ".items | length",
        total: ".orderTotal",
        paymentId: ".transactionId",
        trackingNumber: ".trackingNumber",
        estimatedDelivery: ".estimatedDelivery"
      }
    next: EmitOrderCompleted
  
  - name: EmitOrderCompleted
    type: emit
    data:
      eventType: "OrderCompleted"
      payload:
        orderId: ".orderId"
        customerId: ".customerId"
        status: ".orderStatus"
        completedAt: ".completedAt"
        summary: ".summary"
    end: true
  
  # Error handling tasks
  - name: RejectOrder
    type: set
    data:
      orderStatus: "REJECTED"
      rejectionReason: ".validationMessage"
    next: NotifyRejection
  
  - name: NotifyRejection
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "ORDER_REJECTED"
        subject: "Your order could not be processed"
        message: ".rejectionReason"
    next: FinalizeRejection
  
  - name: FinalizeRejection
    type: set
    data:
      completedAt: "$WORKFLOW.currentTime"
      summary: {
        orderId: ".orderId",
        customerId: ".customerId",
        status: ".orderStatus",
        reason: ".rejectionReason"
      }
    next: EmitOrderRejected
  
  - name: EmitOrderRejected
    type: emit
    data:
      eventType: "OrderRejected"
      payload:
        orderId: ".orderId"
        customerId: ".customerId"
        status: ".orderStatus"
        reason: ".rejectionReason"
        completedAt: ".completedAt"
    end: true
  
  - name: HandleInvalidCustomer
    type: set
    data:
      orderStatus: "REJECTED"
      rejectionReason: "Invalid customer account"
    next: NotifyRejection
  
  - name: HandleValidationError
    type: set
    data:
      orderStatus: "ERROR"
      errorType: "VALIDATION_ERROR"
      errorMessage: "Customer validation failed"
      errorDetails: "$WORKFLOW.error"
    next: NotifyError
  
  - name: HandleInventoryError
    type: set
    data:
      orderStatus: "ERROR"
      errorType: "INVENTORY_ERROR"
      errorMessage: "Failed to check inventory availability"
      errorDetails: "$WORKFLOW.error"
    next: NotifyError
  
  - name: HandleUnavailableInventory
    type: set
    data:
      orderStatus: "REJECTED"
      rejectionReason: "Items are unavailable"
      unavailableItems: ".unavailableItems"
    next: NotifyUnavailableItems
  
  - name: NotifyUnavailableItems
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "ITEMS_UNAVAILABLE"
        subject: "Items in your order are unavailable"
        message: "Unfortunately, some items in your order are currently unavailable."
        details:
          orderId: ".orderId"
          unavailableItems: ".unavailableItems | map(.productId)"
    next: FinalizeRejection
  
  - name: HandlePaymentRejected
    type: set
    data:
      orderStatus: "PAYMENT_FAILED"
      rejectionReason: "Payment was declined"
      declineReason: ".declineReason || 'Unknown decline reason'"
    next: NotifyPaymentDeclined
  
  - name: NotifyPaymentDeclined
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "PAYMENT_DECLINED"
        subject: "Your payment was declined"
        message: "The payment for your order was declined: .declineReason"
    next: FinalizeRejection
  
  - name: HandlePaymentError
    type: set
    data:
      orderStatus: "ERROR"
      errorType: "PAYMENT_ERROR"
      errorMessage: "Payment processing failed"
      errorDetails: "$WORKFLOW.error"
    next: NotifyError
  
  - name: HandlePaymentRejection
    type: set
    data:
      orderStatus: "PAYMENT_FAILED"
      rejectionReason: "Payment status: .status"
    next: NotifyPaymentDeclined
  
  - name: RollbackPayment
    type: call
    function: processPayment
    data:
      body:
        operation: "REFUND"
        customerId: ".customerId"
        orderId: ".orderId"
        paymentId: ".transactionId"
        amount: ".orderTotal"
        reason: "Inventory allocation failed"
    next: HandleAllocationError
  
  - name: HandleAllocationError
    type: set
    data:
      orderStatus: "ERROR"
      errorType: "ALLOCATION_ERROR"
      errorMessage: "Failed to allocate inventory"
      errorDetails: "$WORKFLOW.error"
    next: NotifyError
  
  - name: RollbackAllocation
    type: call
    function: allocateInventory
    data:
      body:
        operation: "RELEASE"
        orderId: ".orderId"
        items: ".items | map({productId: .productId, quantity: .quantity})"
    next: RollbackPayment
  
  - name: HandleShipmentTimeout
    type: set
    data:
      orderStatus: "PROCESSING"
      message: "Shipment update not received within expected timeframe"
    next: CheckShipmentStatus
  
  - name: CheckShipmentStatus
    type: call
    function: createShipment
    data:
      operation: "STATUS"
      shipmentId: ".shipmentId"
    next: ProcessManualShipmentCheck
  
  - name: ProcessManualShipmentCheck
    type: set
    data:
      shipmentStatus: ".status"
      trackingNumber: ".trackingNumber || 'Not available'"
      estimatedDelivery: ".estimatedDelivery || 'Unknown'"
      carrierName: ".carrier || 'Unknown'"
    next: SendShipmentFollowup
  
  - name: SendShipmentFollowup
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "SHIPMENT_UPDATE"
        subject: "Your order is on its way"
        message: "Your order is currently being processed by our shipping department."
        details:
          orderId: ".orderId"
          status: ".shipmentStatus"
          trackingNumber: ".trackingNumber"
    next: CompleteOrder
  
  - name: NotifyError
    type: call
    function: sendNotification
    data:
      body:
        to: ".customerId"
        type: "ORDER_ERROR"
        subject: "There was a problem with your order"
        message: ".errorMessage"
        details:
          orderId: ".orderId"
          status: ".orderStatus"
          errorType: ".errorType"
    next: FinalizeError
  
  - name: FinalizeError
    type: set
    data:
      completedAt: "$WORKFLOW.currentTime"
      summary: {
        orderId: ".orderId",
        customerId: ".customerId",
        status: ".orderStatus",
        errorType: ".errorType",
        errorMessage: ".errorMessage"
      }
    next: EmitOrderError
  
  - name: EmitOrderError
    type: emit
    data:
      eventType: "OrderError"
      payload:
        orderId: ".orderId"
        customerId: ".customerId"
        status: ".orderStatus"
        errorType: ".errorType"
        errorMessage: ".errorMessage"
        completedAt: ".completedAt"
    end: true
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

To test this workflow locally:

```bash
# Start the workflow with a test order
lemline workflow run order-processing.yaml --input orders/test-order.json

# Simulate a shipment status update event
lemline events publish ShipmentStatusUpdate '{
  "orderId": "ORD-12345",
  "status": "SHIPPED",
  "trackingNumber": "TRK123456789",
  "carrier": "Express Shipping",
  "estimatedDelivery": "2023-12-24T12:00:00Z"
}'
```

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
3. **Selective Persistence**: Configure Lemline to minimize database usage for shorter paths
4. **Event Streaming**: Use streaming responses for higher throughput
5. **Response Caching**: Cache customer or product data that doesn't change frequently