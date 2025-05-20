---
title: How to emit and listen for events
---

# How to emit and listen for events

This guide explains how to work with events in Lemline workflows, covering how to emit events to external systems and listen for incoming events. You'll learn how to implement event-driven patterns, correlate events, and build responsive workflows.

## Understanding Events in Lemline

Events are a core component of Lemline's architecture, enabling:

- Communication between workflows and external systems
- Creation of long-running, event-driven processes
- Implementation of complex event correlation patterns
- Building of responsive, reactive workflows

Lemline provides two primary task types for working with events:

1. **Emit Task**: Publishes events to external systems (message brokers)
2. **Listen Task**: Waits for and processes incoming events

## Emitting Events

The `emit` task allows workflows to publish events to message brokers or other event-driven systems.

### Basic Event Emission

Here's a simple example of emitting an event:

```yaml
- name: NotifyOrderCreated
  type: emit
  data:
    eventType: "OrderCreated"
    payload:
      orderId: ".orderId"
      customerId: ".customerId"
      timestamp: "$WORKFLOW.currentTime"
      total: ".total"
  next: NextTask
```

### Event Structure

Events emitted by Lemline have this basic structure:

```json
{
  "id": "evt-abcd1234",
  "type": "OrderCreated",
  "source": "workflow/order-processing",
  "time": "2023-09-10T14:30:00Z",
  "data": {
    "orderId": "ORD-123",
    "customerId": "CUST-456",
    "timestamp": "2023-09-10T14:30:00Z",
    "total": 129.99
  },
  "context": {
    "workflowId": "order-processing",
    "instanceId": "8f7e6d5c-4b3a-2c1d-0b9a-8f7e6d5c4b3a"
  }
}
```

### Configuring Event Properties

You can customize various aspects of the emitted event:

```yaml
- name: EmitCustomEvent
  type: emit
  data:
    eventType: "PaymentProcessed"
    eventSource: "payment-service"
    eventId: ".transactionId"  # Custom event ID
    correlationId: ".orderId"  # For event correlation
    payload:
      orderId: ".orderId"
      amount: ".amount"
      status: ".status"
      timestamp: "$WORKFLOW.currentTime"
  next: NextTask
```

### Specifying Event Destinations

By default, events are emitted to the default event channel, but you can specify custom destinations:

```yaml
- name: EmitToSpecificChannel
  type: emit
  data:
    eventType: "InventoryUpdated"
    payload: 
      productId: ".productId"
      quantity: ".newQuantity"
    destination:
      channel: "inventory-events"
  next: NextTask
```

For Kafka-specific configurations:

```yaml
- name: EmitToKafkaTopic
  type: emit
  data:
    eventType: "UserRegistered"
    payload:
      userId: ".userId"
      email: ".email"
    destination:
      type: "kafka"
      topic: "user-events"
      key: ".userId"  # Partition key
      headers:
        version: "1.0"
        tenant: ".tenantId"
  next: NextTask
```

For RabbitMQ:

```yaml
- name: EmitToRabbitMQ
  type: emit
  data:
    eventType: "MetricCollected"
    payload:
      metricName: ".name"
      value: ".value"
    destination:
      type: "rabbitmq"
      exchange: "metrics"
      routingKey: "system.cpu"
      properties:
        contentType: "application/json"
        priority: 5
  next: NextTask
```

### Error Handling for Emit Tasks

When emitting events, it's important to handle potential failures:

```yaml
- name: AttemptEmitEvent
  type: try
  retry:
    maxAttempts: 3
    interval: PT2S
  catch:
    - error: "*"
      next: HandleEmitError
  do:
    - name: EmitOrderEvent
      type: emit
      data:
        eventType: "OrderCreated"
        payload:
          orderId: ".orderId"
          customerId: ".customerId"
  next: ContinueProcessing
```

## Listening for Events

The `listen` task allows workflows to wait for and process incoming events from external systems.

### Basic Event Listening

Here's a simple example of listening for an event:

```yaml
- name: WaitForPayment
  type: listen
  events:
    - name: PaymentReceived
      type: "PaymentReceived"
      correlations:
        - orderId = .orderId
      consume: 1
  next: ProcessPayment
```

This task pauses workflow execution until a `PaymentReceived` event with a matching `orderId` is received.

### Event Correlation

Event correlation is a key feature that allows workflows to wait for specific events related to the current workflow instance:

```yaml
- name: WaitForShipment
  type: listen
  events:
    - name: ShipmentCreated
      type: "ShipmentCreated"
      correlations:
        - orderId = .orderId
      consume: 1
      timeouts:
        eventTimeout: PT1H
        eventTimeoutNext: HandleShipmentTimeout
  next: NotifyCustomerOfShipment
```

The `correlations` property contains conditions that must be met for an event to match. The left side refers to a field in the incoming event, and the right side refers to a field in the current workflow context.

### Multiple Correlation Conditions

You can specify multiple correlation conditions:

```yaml
- name: WaitForSpecificRefund
  type: listen
  events:
    - name: RefundProcessed
      type: "RefundProcessed"
      correlations:
        - orderId = .orderId
        - amount = .refundAmount
        - customerId = .customerId
      consume: 1
  next: FinalizeRefund
```

All conditions must match for the event to be consumed.

### Waiting for Multiple Event Types

You can wait for different types of events simultaneously:

```yaml
- name: WaitForOrderFeedback
  type: listen
  events:
    - name: OrderCancellation
      type: "OrderCancelled"
      correlations:
        - orderId = .orderId
      consume: 1
      next: HandleCancellation
    - name: OrderReview
      type: "ReviewSubmitted"
      correlations:
        - orderId = .orderId
      consume: 1
      next: ProcessReview
  timeouts:
    eventTimeout: PT72H
    eventTimeoutNext: CloseOrderProcessing
```

In this example, the workflow will continue to either `HandleCancellation` or `ProcessReview` depending on which event arrives first.

### Consuming Multiple Events (Fan-In Pattern)

The `listen` task can collect multiple matching events before continuing:

```yaml
- name: CollectDeliveryConfirmations
  type: listen
  events:
    - name: DeliveryConfirmations
      type: "PackageDelivered"
      correlations:
        - orderId = .orderId
      consume: 3  # Wait for 3 matching events
      timeouts:
        eventTimeout: PT48H
        eventTimeoutNext: HandleIncompleteDelivery
  next: CompleteMultiPackageOrder
```

This example waits for three `PackageDelivered` events for the same order before continuing.

### Continuous Event Processing (Consume Forever)

You can process a continuous stream of events:

```yaml
- name: ProcessSensorReadings
  type: listen
  events:
    - name: SensorReadings
      type: "SensorReading"
      correlations:
        - deviceId = .deviceId
      consume: forever
      foreach:
        as: "reading"
        next: ProcessReading
  next: FinalizeSensorProcessing
```

The `foreach` property specifies how to process each event, and the workflow will continue processing events until explicitly ended or timed out.

### Conditional Event Consumption

You can continue listening until a specific condition is met:

```yaml
- name: MonitorTemperature
  type: listen
  events:
    - name: TemperatureReadings
      type: "TemperatureReading"
      correlations:
        - sensorId = .sensorId
      consume: while: ".events.TemperatureReadings | map(.temperature) | max < 30"
      foreach:
        as: "reading"
        next: LogReading
  next: HandleHighTemperature
```

This example continues collecting temperature readings until a reading with a temperature of 30 or higher is received.

### Event Timeouts

It's important to handle cases where expected events don't arrive:

```yaml
- name: WaitForApproval
  type: listen
  events:
    - name: ApprovalDecision
      type: "ApprovalDecision"
      correlations:
        - requestId = .requestId
      consume: 1
  timeouts:
    eventTimeout: PT24H
    eventTimeoutNext: HandleApprovalTimeout
  next: ProcessApprovalDecision
```

If no matching event is received within 24 hours, the workflow will continue to the `HandleApprovalTimeout` task.

### Reading Event Data

After the `listen` task completes, events are available in the workflow context:

```yaml
- name: ProcessPayment
  type: set
  data:
    paymentEvent: ".events.PaymentReceived[0]"  # First (or only) event
    transactionId: ".paymentEvent.transactionId"
    amount: ".paymentEvent.amount"
    paymentMethod: ".paymentEvent.method"
    message: "Received payment of .amount via .paymentMethod"
  next: CompleteOrder
```

If collecting multiple events, you can access them as an array:

```yaml
- name: AnalyzeMultipleEvents
  type: set
  data:
    allEvents: ".events.DeliveryConfirmations"  # Array of events
    eventCount: ".allEvents | length"
    timestamps: ".allEvents | map(.timestamp)"
    lastEvent: ".allEvents[-1]"  # Last event in the array
    message: "Received .eventCount delivery confirmations"
  next: NextTask
```

## Advanced Event Patterns

### Event-Driven State Machines

Events can be used to implement state machines:

```yaml
- name: InitializeStateMachine
  type: set
  data:
    currentState: "ORDER_CREATED"
    possibleStates: ["ORDER_CREATED", "PAYMENT_RECEIVED", "SHIPMENT_CREATED", "DELIVERED", "CANCELLED"]
    validTransitions:
      ORDER_CREATED: ["PAYMENT_RECEIVED", "CANCELLED"]
      PAYMENT_RECEIVED: ["SHIPMENT_CREATED", "CANCELLED"]
      SHIPMENT_CREATED: ["DELIVERED", "CANCELLED"]
      DELIVERED: []
      CANCELLED: []
  next: WaitForStateChange

- name: WaitForStateChange
  type: listen
  events:
    - name: PaymentEvent
      type: "PaymentReceived"
      correlations:
        - orderId = .orderId
      consume: 1
      next: TransitionToPaymentReceived
    - name: ShipmentEvent
      type: "ShipmentCreated"
      correlations:
        - orderId = .orderId
      consume: 1
      next: TransitionToShipmentCreated
    - name: DeliveryEvent
      type: "OrderDelivered"
      correlations:
        - orderId = .orderId
      consume: 1
      next: TransitionToDelivered
    - name: CancellationEvent
      type: "OrderCancelled"
      correlations:
        - orderId = .orderId
      consume: 1
      next: TransitionToCancelled
  next: HandleUnexpectedEvent

- name: TransitionToPaymentReceived
  type: set
  data:
    previousState: ".currentState"
    requestedState: "PAYMENT_RECEIVED"
    isValidTransition: ".validTransitions[.currentState] | contains([.requestedState])"
  next: ValidateTransition
```

### Event Aggregation and Analysis

You can collect and analyze multiple events:

```yaml
- name: AggregateMetrics
  type: listen
  events:
    - name: MetricEvents
      type: "MetricReported"
      correlations:
        - systemId = .systemId
      consume: amount: 100
      timeouts:
        eventTimeout: PT5M
  next: AnalyzeMetrics

- name: AnalyzeMetrics
  type: set
  data:
    metrics: ".events.MetricEvents"
    cpuMetrics: ".metrics | map(select(.name == 'cpu'))"
    memoryMetrics: ".metrics | map(select(.name == 'memory'))"
    avgCpu: ".cpuMetrics | map(.value) | add / .cpuMetrics | length"
    avgMemory: ".memoryMetrics | map(.value) | add / .memoryMetrics | length"
    maxCpu: ".cpuMetrics | max_by(.value).value"
    isHighUsage: ".avgCpu > 80 || .avgMemory > 90"
  next: CheckResourceUsage
```

### Event-Based Timeouts

You can implement timeout patterns using events:

```yaml
- name: InitiateApprovalProcess
  type: fork
  branches:
    - name: WaitForApproval
      tasks:
        - name: ListenForApproval
          type: listen
          events:
            - name: ApprovalEvent
              type: "ApprovalReceived"
              correlations:
                - requestId = .requestId
              consume: 1
    - name: TimeoutBranch
      tasks:
        - name: EmitTimeout
          type: wait
          data:
            duration: PT24H
          next: EmitTimeoutEvent
        - name: EmitTimeoutEvent
          type: emit
          data:
            eventType: "ApprovalTimeout"
            payload:
              requestId: ".requestId"
              message: "Approval request timed out"
  next: CheckApprovalOutcome

- name: CheckApprovalOutcome
  type: switch
  conditions:
    - condition: ".events.ApprovalEvent != null"
      next: HandleApproval
    - condition: true
      next: HandleTimeout
```

### Event-Based Circuit Breakers

You can implement circuit breaker patterns:

```yaml
- name: MonitorServiceHealth
  type: listen
  events:
    - name: ErrorEvents
      type: "ServiceError"
      correlations:
        - serviceId = .serviceId
      consume: amount: 5
      timeouts:
        eventTimeout: PT1M
        eventTimeoutNext: ServiceHealthy
  next: TripCircuitBreaker

- name: TripCircuitBreaker
  type: emit
  data:
    eventType: "CircuitBreakerTripped"
    payload:
      serviceId: ".serviceId"
      errorCount: ".events.ErrorEvents | length"
      timestamp: "$WORKFLOW.currentTime"
  next: WaitForRecovery

- name: WaitForRecovery
  type: wait
  data:
    duration: PT5M
  next: ResetCircuitBreaker
```

## Real-World Example: Multi-Step Approval Process

Here's a complete example of an event-driven approval workflow:

```yaml
id: approval-workflow
name: Multi-step Approval Process
version: '1.0'
specVersion: '1.0'
start: InitiateApproval
tasks:
  - name: InitiateApproval
    type: set
    data:
      requestId: "$WORKFLOW.input.requestId"
      requestType: "$WORKFLOW.input.requestType"
      requestorId: "$WORKFLOW.input.requestorId"
      amount: "$WORKFLOW.input.amount"
      description: "$WORKFLOW.input.description"
      approvalStatus: "PENDING"
      requiredApprovals: 2
      approvals: []
      rejections: []
    next: NotifyApprovers
  
  - name: NotifyApprovers
    type: emit
    data:
      eventType: "ApprovalRequested"
      payload:
        requestId: ".requestId"
        requestType: ".requestType"
        requestorId: ".requestorId"
        amount: ".amount"
        description: ".description"
        requiredApprovals: ".requiredApprovals"
    next: WaitForApprovals
  
  - name: WaitForApprovals
    type: listen
    events:
      - name: ApprovalResponses
        type: "ApprovalResponse"
        correlations:
          - requestId = .requestId
        consume: while: ".events.ApprovalResponses | map(select(.decision == \"APPROVED\")) | length < .requiredApprovals && .events.ApprovalResponses | map(select(.decision == \"REJECTED\")) | length == 0"
        foreach:
          as: "response"
          next: ProcessApprovalResponse
    timeouts:
      eventTimeout: PT72H
      eventTimeoutNext: HandleApprovalTimeout
    next: EvaluateApprovalStatus
  
  - name: ProcessApprovalResponse
    type: set
    data:
      currentResponse: ".response"
      approverName: ".currentResponse.approverName"
      decision: ".currentResponse.decision"
      comments: ".currentResponse.comments"
      approvals: "if .decision == \"APPROVED\" then .approvals + [.currentResponse] else .approvals end"
      rejections: "if .decision == \"REJECTED\" then .rejections + [.currentResponse] else .rejections end"
    next: EmitResponseReceived
  
  - name: EmitResponseReceived
    type: emit
    data:
      eventType: "ApprovalResponseReceived"
      payload:
        requestId: ".requestId"
        approverName: ".approverName"
        decision: ".decision"
        comments: ".comments"
        timestamp: "$WORKFLOW.currentTime"
        approvalCount: ".approvals | length"
        rejectionCount: ".rejections | length"
    end: false
  
  - name: EvaluateApprovalStatus
    type: set
    data:
      allResponses: ".events.ApprovalResponses"
      approvalCount: ".approvals | length"
      rejectionCount: ".rejections | length"
      finalStatus: "if .rejectionCount > 0 then \"REJECTED\" else if .approvalCount >= .requiredApprovals then \"APPROVED\" else \"PENDING\" end end"
    next: FinalizeApproval
  
  - name: FinalizeApproval
    type: switch
    conditions:
      - condition: ".finalStatus == \"APPROVED\""
        next: HandleApproved
      - condition: ".finalStatus == \"REJECTED\""
        next: HandleRejected
      - condition: true
        next: HandlePending
  
  - name: HandleApproved
    type: emit
    data:
      eventType: "RequestApproved"
      payload:
        requestId: ".requestId"
        requestType: ".requestType"
        requestorId: ".requestorId"
        amount: ".amount"
        approvers: ".approvals | map(.approverName)"
        timestamp: "$WORKFLOW.currentTime"
    next: CompleteApproved
  
  - name: CompleteApproved
    type: set
    data:
      status: "APPROVED"
      message: "Request approved by .approvalCount approvers"
      approvers: ".approvals | map(.approverName)"
    end: true
  
  - name: HandleRejected
    type: emit
    data:
      eventType: "RequestRejected"
      payload:
        requestId: ".requestId"
        requestType: ".requestType"
        requestorId: ".requestorId"
        rejectedBy: ".rejections[0].approverName"
        reason: ".rejections[0].comments"
        timestamp: "$WORKFLOW.currentTime"
    next: CompleteRejected
  
  - name: CompleteRejected
    type: set
    data:
      status: "REJECTED"
      message: "Request rejected by .rejections[0].approverName"
      reason: ".rejections[0].comments"
    end: true
  
  - name: HandlePending
    type: set
    data:
      status: "PENDING"
      message: "Request is still pending approval"
      currentApprovals: ".approvalCount"
      requiredApprovals: ".requiredApprovals"
    end: true
  
  - name: HandleApprovalTimeout
    type: emit
    data:
      eventType: "ApprovalTimeout"
      payload:
        requestId: ".requestId"
        requestType: ".requestType"
        requestorId: ".requestorId"
        timestamp: "$WORKFLOW.currentTime"
        currentApprovals: ".approvals | length"
        requiredApprovals: ".requiredApprovals"
    next: CompleteTimeout
  
  - name: CompleteTimeout
    type: set
    data:
      status: "TIMEOUT"
      message: "Request timed out after 72 hours"
      currentApprovals: ".approvals | length"
      requiredApprovals: ".requiredApprovals"
    end: true
```

This workflow demonstrates many event patterns:
1. Emitting an event to notify approvers
2. Listening for approval responses with correlation
3. Processing events as they arrive (foreach)
4. Continuing to collect events until conditions are met
5. Making decisions based on aggregated events
6. Handling timeout conditions
7. Emitting events for the final outcome

## Best Practices for Event Handling

1. **Define Clear Event Structures**: Use consistent event schemas with clear field names
2. **Use Correlation IDs**: Always include correlation identifiers for traceability
3. **Handle Timeouts**: Always specify timeouts for listen tasks
4. **Process Events Efficiently**: Use foreach processing for continuous events
5. **Implement Dead-Letter Handling**: Have a strategy for unprocessable events
6. **Include Proper Timestamps**: Add timestamp information to all events
7. **Consider Event Ordering**: Be mindful of event ordering guarantees (or lack thereof)
8. **Monitor Event Flow**: Implement observability for event processing
9. **Handle Duplicate Events**: Design workflows to be idempotent
10. **Document Event Schemas**: Clear documentation for all emitted and consumed events

## Common Event Patterns

### Command-Query Responsibility Segregation (CQRS)

Separate command and query operations:

```yaml
- name: ProcessCommand
  type: emit
  data:
    eventType: "UpdateInventory"
    payload:
      productId: ".productId"
      quantityChange: ".quantityChange"
      operation: ".operation"
  next: WaitForCompletion

- name: WaitForCompletion
  type: listen
  events:
    - name: InventoryUpdated
      type: "InventoryUpdateCompleted"
      correlations:
        - requestId = .requestId
      consume: 1
  next: QueryCurrentState

- name: QueryCurrentState
  type: call
  function: inventoryService
  data:
    productId: ".productId"
  next: ProcessResult
```

### Event Sourcing

Use events as the system of record:

```yaml
- name: RecordEvent
  type: emit
  data:
    eventType: "OrderStateChanged"
    payload:
      orderId: ".orderId"
      previousState: ".currentState"
      newState: ".newState"
      reason: ".reason"
      timestamp: "$WORKFLOW.currentTime"
  next: UpdateLocalState

- name: UpdateLocalState
  type: set
  data:
    currentState: ".newState"
    stateHistory: ".stateHistory + [{state: .newState, timestamp: $WORKFLOW.currentTime, reason: .reason}]"
  next: NextTask
```

### Saga Pattern

Coordinate distributed transactions with compensation:

```yaml
- name: StartTransaction
  type: emit
  data:
    eventType: "PaymentRequested"
    payload:
      transactionId: ".transactionId"
      amount: ".amount"
      customerId: ".customerId"
  next: WaitForPaymentResult

- name: WaitForPaymentResult
  type: listen
  events:
    - name: PaymentResult
      type: "PaymentProcessed"
      correlations:
        - transactionId = .transactionId
      consume: 1
  next: CheckPaymentSuccess

- name: CheckPaymentSuccess
  type: switch
  conditions:
    - condition: ".events.PaymentResult[0].success == true"
      next: RequestInventoryReservation
    - condition: true
      next: HandlePaymentFailure

- name: RequestInventoryReservation
  type: emit
  data:
    eventType: "InventoryReservationRequested"
    payload:
      transactionId: ".transactionId"
      items: ".items"
  next: WaitForReservationResult

- name: WaitForReservationResult
  type: listen
  events:
    - name: ReservationResult
      type: "InventoryReserved"
      correlations:
        - transactionId = .transactionId
      consume: 1
  next: CheckReservationSuccess

- name: CheckReservationSuccess
  type: switch
  conditions:
    - condition: ".events.ReservationResult[0].success == true"
      next: FinalizeTransaction
    - condition: true
      next: CompensatePayment

- name: CompensatePayment
  type: emit
  data:
    eventType: "PaymentReversal"
    payload:
      transactionId: ".transactionId"
      reason: "Inventory reservation failed"
  next: HandleTransactionFailure
```

## Next Steps

- Learn about [data passing between tasks](lemline-howto-data-passing.md)
- Explore [runtime expressions and jq](lemline-howto-jq.md)
- Understand [error propagation and resilience](lemline-explain-errors.md)