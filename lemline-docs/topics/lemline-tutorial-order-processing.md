---
title: "Tutorial: Database-Less Order Processing"
---

# Tutorial: Database-Less Order Processing

This tutorial demonstrates Lemline's core value proposition: orchestrating a complete order processing workflow without relying on a central database for most operations. You'll create a workflow that handles order validation, payment processing, inventory checks, and shipment notification.

## Learning Objectives

By completing this tutorial, you will learn:

- How to design event-driven workflows that minimize database usage
- How to implement conditional logic for business rule validation
- How to integrate with external services using HTTP calls
- How to handle errors and implement retry strategies
- How to correlate events across multiple systems

## Prerequisites

- Completion of the [Hello, Workflow!](lemline-tutorial-hello.md) tutorial
- Basic understanding of HTTP API concepts
- Lemline runtime installed and configured

## 1. Understanding Database-Less Orchestration

Traditional workflow engines store state in a database for every step. Lemline takes a different approach:

- Uses events to drive workflow progression
- Stores minimal state, only when absolutely necessary
- Leverages existing message brokers for reliability
- Uses optimistic concurrency for better scalability

For this tutorial, we'll build an order processing workflow that demonstrates these principles.

## 2. Setting Up Your Project

Work in the `lemline-project` directory from [Setting Up a Local Environment](lemline-tutorial-setup.md). Keep the worker running with the `examples` namespace enabled.

Create a workflow file named `order-processing.yaml`:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: order-processing
  version: 1.0.0
  title: Order Processing Workflow
use:
  functions:
    checkInventory:
      call: http
      with:
        method: get
        endpoint: https://inventory-api.example.com/check
        query:
          order: ${ . }
    processPayment:
      call: http
      with:
        method: post
        endpoint: https://payments-api.example.com/process
        body:
          order: ${ . }
    createShipment:
      call: http
      with:
        method: post
        endpoint: https://shipping-api.example.com/shipments
        body:
          order: ${ . }
    sendNotification:
      call: http
      with:
        method: post
        endpoint: https://notification-api.example.com/send
        body:
          order: ${ . }
do:
  - receiveOrder:
      set: ${ . }
```

## 3. Implementing Order Validation

Add the order validation task to your workflow:

```yaml
do:
  - ValidateOrder:
      try:
        - ValidateOrderSchema:
            call: validateOrderSchema
            with:
              schema:
                type: object
                required:
                  - "orderId"
                  - "customerId"
                  - "items"
                  - "shippingAddress"
                properties:
                  orderId:
                    type: string
                  customerId:
                    type: string
                  total:
                    type: number
                    minimum: 0.01
                  items:
                    type: array
                    items:
                      type: object
                      required:
                        - "productId"
                        - "quantity"
                        - "price"
              data: ${ $workflow.input }
      catch:
        as: validationError
        then: HandleValidationError
        retry:
          limit:
            attempt:
              count: 1
      then: CalculateTotal
  - HandleValidationError:
      set:
        status: "REJECTED"
        reason: ${ $context.validationError.message }
        orderId: ${ $workflow.input.orderId }
      then: SendRejectionNotification
  - SendRejectionNotification:
      call: sendNotification
      with:
        to: ${ $workflow.input.customerId }
        subject: "Order Rejected"
        message: '${ "Your order \($context.orderId) was rejected: \($context.reason)" }'
      then: end
```

## 4. Adding Business Logic and Payment Processing

Next, add the calculation and payment processing steps:

```yaml
  - CalculateTotal:
      set:
        calculatedTotal: ${ { .items[] | .price * .quantity } | add }
        isValid: ${ .calculatedTotal == .total }
      then: CheckTotal
  - CheckTotal:
      switch:
        - case1:
            then: CheckInventory
            when: ${ .isValid == true }
        - default:
            then: HandlePriceDiscrepancy
  - HandlePriceDiscrepancy:
      set:
        status: "REJECTED"
        reason: "Price discrepancy detected"
        orderId: ${ .orderId }
      then: SendRejectionNotification
  - CheckInventory:
      try:
        - InventoryCheck:
            call: checkInventory
            with:
              items: ${ .items[].productId }
      catch:
        as: failure
        then: HandleInventoryError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT3S
      then: CheckInventoryResult
  - CheckInventoryResult:
      switch:
        - case1:
            then: ProcessPayment
            when: ${ .result.available == true }
        - default:
            then: HandleUnavailableInventory
  - HandleUnavailableInventory:
      set:
        status: "REJECTED"
        reason: "Some items are unavailable"
        orderId: ${ .orderId }
        unavailableItems: ${ .result.unavailableItems }
      then: SendRejectionNotification
  - HandleInventoryError:
      set:
        status: "ERROR"
        reason: "Inventory service error"
        orderId: ${ .orderId }
      then: SendRejectionNotification
  - ProcessPayment:
      try:
        - PaymentProcessing:
            call: processPayment
            with:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              amount: ${ .total }
              currency: "USD"
      catch:
        as: failure
        then: HandlePaymentError
        retry:
          limit:
            attempt:
              count: 2
          delay: PT5S
      then: CheckPaymentResult
  - CheckPaymentResult:
      switch:
        - case1:
            then: CreateShipment
            when: ${ .result.status == "APPROVED" }
        - default:
            then: HandlePaymentRejection
  - HandlePaymentRejection:
      set:
        status: "PAYMENT_REJECTED"
        reason: ${ .result.reason // "Payment was declined" }
        orderId: ${ .orderId }
      then: SendRejectionNotification
  - HandlePaymentError:
      set:
        status: "ERROR"
        reason: "Payment processing error"
        orderId: ${ .orderId }
      then: SendRejectionNotification
```

## 5. Implementing Shipment and Notification

Finally, add the shipment and notification tasks:

```yaml
  - CreateShipment:
      try:
        - ShipmentCreation:
            call: createShipment
            with:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              items: ${ .items }
              shippingAddress: ${ .shippingAddress }
      catch:
        as: failure
        then: HandleShipmentError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT3S
      then: SendConfirmationNotification
  - HandleShipmentError:
      set:
        status: "ERROR"
        reason: "Shipment creation error"
        orderId: ${ .orderId }
      then: SendRejectionNotification
  - SendConfirmationNotification:
      call: sendNotification
      with:
        to: ${ .customerId }
        subject: "Order Confirmed"
        message: ${ "Your order \(.orderId) has been confirmed and will be shipped to \(.shippingAddress)" }
      then: end
```

## 6. Testing the Workflow

To test this workflow, create a sample order input file `order.json`:

```json
{
  "orderId": "ORD-12345",
  "customerId": "CUST-789",
  "total": 129.95,
  "items": [
    {
      "productId": "PROD-001",
      "quantity": 2,
      "price": 49.95
    },
    {
      "productId": "PROD-002",
      "quantity": 1,
      "price": 30.05
    }
  ],
  "shippingAddress": {
    "street": "123 Main St",
    "city": "Anytown",
    "state": "CA",
    "zip": "12345",
    "country": "US"
  }
}
```

Since this tutorial assumes external services, you can either:
1. Mock the services using tools like Mockoon or WireMock
2. Replace the HTTP calls with custom function calls that simulate the services

Run the workflow:

```bash
bin/lemline definition post -f order-processing.yaml
bin/lemline instance start examples order-processing --input "$(cat order.json)"
```

## 7. Database-Less Operation Analysis

Let's analyze how this workflow minimizes database usage:

1. **Stateless Validation**: Order validation happens entirely in memory without database writes
2. **Event-Driven Progression**: Each step completes and triggers the next step via events
3. **Selective Persistence**: Only certain operations (like payment processing confirmations) would require persistence in a real-world scenario
4. **Error Handling Without State**: Retry logic operates without requiring database state
5. **Correlation Through Events**: The orderId flows through the system allowing event correlation

Traditional workflow engines would write state to a database:
- After completing each task
- When transitioning between tasks
- For every retry attempt
- For error handling state

In contrast, Lemline uses:
- In-memory state for most operations
- Message broker for reliable messaging
- Database only when absolutely necessary (long waits, correlated events)

## 8. Event Correlation

Let's add an event correlation example to our workflow by modifying the shipment step to wait for a shipment confirmation:

```yaml
  - CreateShipment:
      try:
        - ShipmentCreation:
            call: createShipment
            with:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              items: ${ .items }
              shippingAddress: ${ .shippingAddress }
      catch:
        as: failure
        then: HandleShipmentError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT3S
      then: WaitForShipmentConfirmation
  - WaitForShipmentConfirmation:
      try:
        - WaitForShipmentConfirmationEvents:
            listen:
              to:
                one:
                  with:
                    type: ShipmentConfirmed
                  correlate:
                    orderId:
                      from: ${ .data.orderId }
                      expect: ${ .orderId }
            timeout:
              after: PT1H
      catch:
        errors:
          with:
            status: 408
        then: HandleShipmentTimeout
      then: SendConfirmationNotification
  - HandleShipmentTimeout:
      set:
        status: "PENDING"
        reason: "Shipment is taking longer than expected, but will be processed"
        orderId: ${ .orderId }
      then: SendDelayNotification
  - SendDelayNotification:
      call: sendNotification
      with:
        to: ${ .customerId }
        subject: "Order Processing Delayed"
        message: ${ "Your order \(.orderId) is being processed, but shipment may take longer than expected" }
      then: end
```

This example demonstrates how Lemline handles event correlation - one of the few cases where a small amount of database state is actually beneficial.

## What You've Learned

In this tutorial, you've learned how to:

- Create a complete order processing workflow without heavy database dependencies
- Implement business logic validation through conditional tasks
- Integrate with external services using HTTP calls
- Handle error scenarios with retry policies and custom error handlers
- Use event correlation for cross-service coordination
- Understand when database state is necessary and when it can be avoided

## Next Steps

To further explore Lemline's capabilities:

- Learn how to work with [Waits, Fan-In, and Timers](lemline-tutorial-waits.md) for more complex coordination scenarios
- Explore [How to make HTTP calls](lemline-howto-http.md) for more advanced integration patterns
- Understand [Event correlation](lemline-explain-fan-in.md) for complex event processing
- Study [Error propagation and resilience](lemline-explain-errors.md) to build highly reliable workflows