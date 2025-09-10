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

Create a new directory for your order processing project:

```bash
mkdir order-processing
cd order-processing
```

Create a workflow file named `order-processing.yaml`:

```yaml
id: order-processing
name: Order Processing Workflow
version: '1.0'
specVersion: '1.0'
start: ValidateOrder
input:
  - $WORKFLOW.input
functions:
  - name: validateOrderSchema
    type: custom
    operation: jsonSchemaValidation
  - name: checkInventory
    type: http
    operation: GET
    url: https://inventory-api.example.com/check
  - name: processPayment
    type: http
    operation: POST 
    url: https://payments-api.example.com/process
  - name: createShipment
    type: http
    operation: POST
    url: https://shipping-api.example.com/shipments
  - name: sendNotification
    type: http
    operation: POST
    url: https://notification-api.example.com/send
```

## 3. Implementing Order Validation

Add the order validation task to your workflow:

```yaml
tasks:
  - name: ValidateOrder
    type: try
    retry:
      maxAttempts: 1
    catch:
      - error: "*"
        as: validationError
        next: HandleValidationError
    do:
      - name: ValidateOrderSchema
        type: call
        function: validateOrderSchema
        data:
          schema:
            type: object
            required: ["orderId", "customerId", "items", "shippingAddress"]
            properties:
              orderId: { type: string }
              customerId: { type: string }
              total: { type: number, minimum: 0.01 }
              items:
                type: array
                items:
                  type: object
                  required: ["productId", "quantity", "price"]
          data: "$WORKFLOW.input"
    next: CalculateTotal
  
  - name: HandleValidationError
    type: set
    data:
      status: "REJECTED"
      reason: "$WORKFLOW.validationError.message"
      orderId: "$WORKFLOW.input.orderId"
    next: SendRejectionNotification
  
  - name: SendRejectionNotification
    type: call
    function: sendNotification
    data:
      to: "$WORKFLOW.input.customerId"
      subject: "Order Rejected"
      message: "Your order $WORKFLOW.orderId was rejected: $WORKFLOW.reason"
    end: true
```

## 4. Adding Business Logic and Payment Processing

Next, add the calculation and payment processing steps:

```yaml
  - name: CalculateTotal
    type: set
    data:
      calculatedTotal: "{ .items[] | .price * .quantity } | add"
      isValid: ".calculatedTotal == .total"
    next: CheckTotal
  
  - name: CheckTotal
    type: switch
    conditions:
      - condition: ".isValid == true"
        next: CheckInventory
      - condition: true
        next: HandlePriceDiscrepancy
  
  - name: HandlePriceDiscrepancy
    type: set
    data:
      status: "REJECTED"
      reason: "Price discrepancy detected"
      orderId: ".orderId"
    next: SendRejectionNotification
  
  - name: CheckInventory
    type: try
    retry:
      maxAttempts: 3
      interval: PT3S
      multiplier: 2
      jitter: 0.3
    catch:
      - error: "*"
        next: HandleInventoryError
    do:
      - name: InventoryCheck
        type: call
        function: checkInventory
        data:
          items: ".items[].productId"
    next: CheckInventoryResult
  
  - name: CheckInventoryResult
    type: switch
    conditions:
      - condition: ".result.available == true"
        next: ProcessPayment
      - condition: true
        next: HandleUnavailableInventory
  
  - name: HandleUnavailableInventory
    type: set
    data:
      status: "REJECTED"
      reason: "Some items are unavailable"
      orderId: ".orderId"
      unavailableItems: ".result.unavailableItems"
    next: SendRejectionNotification
  
  - name: HandleInventoryError
    type: set
    data:
      status: "ERROR"
      reason: "Inventory service error"
      orderId: ".orderId"
    next: SendRejectionNotification
  
  - name: ProcessPayment
    type: try
    retry:
      maxAttempts: 2
      interval: PT5S
    catch:
      - error: "*"
        next: HandlePaymentError
    do:
      - name: PaymentProcessing
        type: call
        function: processPayment
        data:
          orderId: ".orderId"
          customerId: ".customerId"
          amount: ".total"
          currency: "USD"
    next: CheckPaymentResult
  
  - name: CheckPaymentResult
    type: switch
    conditions:
      - condition: ".result.status == 'APPROVED'"
        next: CreateShipment
      - condition: true
        next: HandlePaymentRejection
  
  - name: HandlePaymentRejection
    type: set
    data:
      status: "PAYMENT_REJECTED"
      reason: ".result.reason || 'Payment was declined'"
      orderId: ".orderId"
    next: SendRejectionNotification
  
  - name: HandlePaymentError
    type: set
    data:
      status: "ERROR"
      reason: "Payment processing error"
      orderId: ".orderId"
    next: SendRejectionNotification
```

## 5. Implementing Shipment and Notification

Finally, add the shipment and notification tasks:

```yaml
  - name: CreateShipment
    type: try
    retry:
      maxAttempts: 3
      interval: PT3S
    catch:
      - error: "*"
        next: HandleShipmentError
    do:
      - name: ShipmentCreation
        type: call
        function: createShipment
        data:
          orderId: ".orderId"
          customerId: ".customerId"
          items: ".items"
          shippingAddress: ".shippingAddress"
    next: SendConfirmationNotification
  
  - name: HandleShipmentError
    type: set
    data:
      status: "ERROR"
      reason: "Shipment creation error"
      orderId: ".orderId"
    next: SendRejectionNotification
  
  - name: SendConfirmationNotification
    type: call
    function: sendNotification
    data:
      to: ".customerId"
      subject: "Order Confirmed"
      message: "Your order .orderId has been confirmed and will be shipped to .shippingAddress"
    end: true
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
java -jar lemline-runner.jar workflow run order-processing.yaml --input order.json
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
  - name: CreateShipment
    type: try
    retry:
      maxAttempts: 3
      interval: PT3S
    catch:
      - error: "*"
        next: HandleShipmentError
    do:
      - name: ShipmentCreation
        type: call
        function: createShipment
        data:
          orderId: ".orderId"
          customerId: ".customerId"
          items: ".items"
          shippingAddress: ".shippingAddress"
    next: WaitForShipmentConfirmation
  
  - name: WaitForShipmentConfirmation
    type: listen
    events:
      - name: ShipmentConfirmed
        type: ShipmentConfirmed
        correlations:
          - orderId = .orderId
        consume: 1
        timeouts:
          eventTimeout: PT1H
          eventTimeoutNext: HandleShipmentTimeout
    next: SendConfirmationNotification
  
  - name: HandleShipmentTimeout
    type: set
    data:
      status: "PENDING"
      reason: "Shipment is taking longer than expected, but will be processed"
      orderId: ".orderId"
    next: SendDelayNotification
  
  - name: SendDelayNotification
    type: call
    function: sendNotification
    data:
      to: ".customerId"
      subject: "Order Processing Delayed"
      message: "Your order .orderId is being processed, but shipment may take longer than expected"
    end: true
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