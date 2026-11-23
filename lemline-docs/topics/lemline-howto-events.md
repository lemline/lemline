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
  - NotifyOrderCreated:
      emit:
        event:
          with:
            type: "OrderCreated"
            data:
              orderId: ${ .orderId }
              customerId: ${ .customerId }
              timestamp: ${ $context.currentTime }
              total: ${ .total }
      then: NextTask
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
  - EmitCustomEvent:
      emit:
        event:
          with:
            type: "PaymentProcessed"
            data:
              orderId: ${ .orderId }
              amount: ${ .amount }
              status: ${ .status }
              timestamp: ${ $context.currentTime }
      then: NextTask
```

### Specifying Event Destinations

By default, events are emitted to the default event channel, but you can specify custom destinations:

```yaml
  - EmitToSpecificChannel:
      emit:
        event:
          with:
            type: "InventoryUpdated"
            data:
              productId: ${ .productId }
              quantity: ${ .newQuantity }
      then: NextTask
```

For Kafka-specific configurations:

```yaml
  - EmitToKafkaTopic:
      emit:
        event:
          with:
            type: "UserRegistered"
            data:
              userId: ${ .userId }
              email: ${ .email }
      then: NextTask
```

For RabbitMQ:

```yaml
  - EmitToRabbitMQ:
      emit:
        event:
          with:
            type: "MetricCollected"
            data:
              metricName: ${ .name }
              value: ${ .value }
      then: NextTask
```

### Error Handling for Emit Tasks

When emitting events, it's important to handle potential failures:

```yaml
  - AttemptEmitEvent:
      try:
        - EmitOrderEvent:
            emit:
              event:
                with:
                  type: "OrderCreated"
                  data:
                    orderId: ${ .orderId }
                    customerId: ${ .customerId }
      catch:
        as: failure
        then: HandleEmitError
        retry:
          limit:
            attempt:
              count: 3
          delay: PT2S
      then: ContinueProcessing
```

## Listening for Events

The `listen` task allows workflows to wait for and process incoming events from external systems.

### Basic Event Listening

Here's a simple example of listening for an event:

```yaml
  - WaitForPayment:
      listen:
        to:
          one:
            with:
              type: "PaymentReceived"
            correlate:
              orderId:
                from: ${ .data.orderId }
                expect: ${ .orderId }
      then: ProcessPayment
```

This task pauses workflow execution until a `PaymentReceived` event with a matching `orderId` is received.

### Event Correlation

Event correlation is a key feature that allows workflows to wait for specific events related to the current workflow instance:

```yaml
  - WaitForShipment:
      try:
        - WaitForShipmentEvents:
            listen:
              to:
                one:
                  with:
                    type: "ShipmentCreated"
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
      then: NotifyCustomerOfShipment
```

The `correlations` property contains conditions that must be met for an event to match. The left side refers to a field in the incoming event, and the right side refers to a field in the current workflow context.

### Multiple Correlation Conditions

You can specify multiple correlation conditions:

```yaml
  - WaitForSpecificRefund:
      listen:
        to:
          one:
            with:
              type: "RefundProcessed"
            correlate:
              orderId:
                from: ${ .data.orderId }
                expect: ${ .orderId }
              amount:
                from: ${ .data.amount }
                expect: ${ .refundAmount }
              customerId:
                from: ${ .data.customerId }
                expect: ${ .customerId }
      then: FinalizeRefund
```

All conditions must match for the event to be consumed.

### Waiting for Multiple Event Types

You can wait for different types of events simultaneously:

```yaml
  - WaitForOrderFeedback:
      try:
        - WaitForOrderFeedbackEvents:
            listen:
              to:
                any:
                  - with:
                      type: "OrderCancelled"
                    correlate:
                      orderId:
                        from: ${ .data.orderId }
                        expect: ${ .orderId }
                  - with:
                      type: "ReviewSubmitted"
                    correlate:
                      orderId:
                        from: ${ .data.orderId }
                        expect: ${ .orderId }
            timeout:
              after: PT72H
      catch:
        errors:
          with:
            status: 408
        then: CloseOrderProcessing
```

In this example, the workflow will continue to either `HandleCancellation` or `ProcessReview` depending on which event arrives first.

### Consuming Multiple Events (Fan-In Pattern)

The `listen` task can collect multiple matching events before continuing:

```yaml
  - CollectDeliveryConfirmations:
      try:
        - CollectDeliveryConfirmationsEvents:
            listen:
              to:
                any:
                  - with:
                      type: "PackageDelivered"
                    correlate:
                      orderId:
                        from: ${ .data.orderId }
                        expect: ${ .orderId }
                until: ${ length >= 3 }
            timeout:
              after: PT48H
      catch:
        errors:
          with:
            status: 408
        then: HandleIncompleteDelivery
      then: CompleteMultiPackageOrder
```

This example waits for three `PackageDelivered` events for the same order before continuing.

### Continuous Event Processing (Consume Forever)

You can process a continuous stream of events:

```yaml
  - ProcessSensorReadings:
      listen:
        to:
          one:
            with:
              type: "SensorReading"
            correlate:
              deviceId:
                from: ${ .data.deviceId }
                expect: ${ .deviceId }
      then: FinalizeSensorProcessing
```

The `foreach` property specifies how to process each event, and the workflow will continue processing events until explicitly ended or timed out.

### Conditional Event Consumption

You can continue listening until a specific condition is met:

```yaml
  - MonitorTemperature:
      listen:
        to:
          any:
            - with:
                type: "TemperatureReading"
              correlate:
                sensorId:
                  from: ${ .data.sensorId }
                  expect: ${ .sensorId }
          until: ${ (.events.TemperatureReadings | map(.temperature) | max < 30) | not }
      then: HandleHighTemperature
```

This example continues collecting temperature readings until a reading with a temperature of 30 or higher is received.

### Event Timeouts

It's important to handle cases where expected events don't arrive:

```yaml
  - WaitForApproval:
      try:
        - WaitForApprovalEvents:
            listen:
              to:
                one:
                  with:
                    type: "ApprovalDecision"
                  correlate:
                    requestId:
                      from: ${ .data.requestId }
                      expect: ${ .requestId }
            timeout:
              after: PT24H
      catch:
        errors:
          with:
            status: 408
        then: HandleApprovalTimeout
      then: ProcessApprovalDecision
```

If no matching event is received within 24 hours, the workflow will continue to the `HandleApprovalTimeout` task.

### Reading Event Data

After the `listen` task completes, events are available in the workflow context:

```yaml
  - ProcessPayment:
      set:
        paymentEvent: ${ .events.PaymentReceived[0] }
        transactionId: ${ .paymentEvent.transactionId }
        amount: ${ .paymentEvent.amount }
        paymentMethod: ${ .paymentEvent.method }
        message: ${ "Received payment of \(.amount) via \(.paymentMethod)" }
      then: CompleteOrder
```

If collecting multiple events, you can access them as an array:

```yaml
  - AnalyzeMultipleEvents:
      set:
        allEvents: ${ .events.DeliveryConfirmations }
        eventCount: ${ .allEvents | length }
        timestamps: ${ .allEvents | map(.timestamp) }
        lastEvent: ${ .allEvents[-1] }
        message: ${ "Received \(.eventCount) delivery confirmations" }
      then: NextTask
```

## Advanced Event Patterns

### Event-Driven State Machines

Events can be used to implement state machines:

```yaml
  - InitializeStateMachine:
      set:
        currentState: "ORDER_CREATED"
        possibleStates:
          - "ORDER_CREATED"
          - "PAYMENT_RECEIVED"
          - "SHIPMENT_CREATED"
          - "DELIVERED"
          - "CANCELLED"
        validTransitions:
          ORDER_CREATED:
            - "PAYMENT_RECEIVED"
            - "CANCELLED"
          PAYMENT_RECEIVED:
            - "SHIPMENT_CREATED"
            - "CANCELLED"
          SHIPMENT_CREATED:
            - "DELIVERED"
            - "CANCELLED"
          DELIVERED: []
          CANCELLED: []
      then: WaitForStateChange
  - WaitForStateChange:
      listen:
        to:
          any:
            - with:
                type: "PaymentReceived"
              correlate:
                orderId:
                  from: ${ .data.orderId }
                  expect: ${ .orderId }
            - with:
                type: "ShipmentCreated"
              correlate:
                orderId:
                  from: ${ .data.orderId }
                  expect: ${ .orderId }
            - with:
                type: "OrderDelivered"
              correlate:
                orderId:
                  from: ${ .data.orderId }
                  expect: ${ .orderId }
            - with:
                type: "OrderCancelled"
              correlate:
                orderId:
                  from: ${ .data.orderId }
                  expect: ${ .orderId }
      then: HandleUnexpectedEvent
  - TransitionToPaymentReceived:
      set:
        previousState: ${ .currentState }
        requestedState: "PAYMENT_RECEIVED"
        isValidTransition: ${ .validTransitions[.currentState] | contains([.requestedState]) }
      then: ValidateTransition
```

### Event Aggregation and Analysis

You can collect and analyze multiple events:

```yaml
  - AggregateMetrics:
      listen:
        to:
          any:
            - with:
                type: "MetricReported"
              correlate:
                systemId:
                  from: ${ .data.systemId }
                  expect: ${ .systemId }
          until: ${ length >= 100 }
      timeout:
        after: PT5M
      then: AnalyzeMetrics
  - AnalyzeMetrics:
      set:
        metrics: ${ .events.MetricEvents }
        cpuMetrics: ${ .metrics | map(select(.name == "cpu")) }
        memoryMetrics: ${ .metrics | map(select(.name == "memory")) }
        avgCpu: ${ .cpuMetrics | map(.value) | add / .cpuMetrics | length }
        avgMemory: ${ .memoryMetrics | map(.value) | add / .memoryMetrics | length }
        maxCpu: ${ .cpuMetrics | max_by(.value).value }
        isHighUsage: ${ .avgCpu > 80 // .avgMemory > 90 }
      then: CheckResourceUsage
```

### Event-Based Timeouts

You can implement timeout patterns using events:

```yaml
  - InitiateApprovalProcess:
      fork:
        branches:
          - WaitForApproval:
              do:
                - ListenForApproval:
                    listen:
                      to:
                        one:
                          with:
                            type: "ApprovalReceived"
                          correlate:
                            requestId:
                              from: ${ .data.requestId }
                              expect: ${ .requestId }
          - TimeoutBranch:
              do:
                - EmitTimeout:
                    wait: PT24H
                    then: EmitTimeoutEvent
                - EmitTimeoutEvent:
                    emit:
                      event:
                        with:
                          type: "ApprovalTimeout"
                          data:
                            requestId: ${ .requestId }
                            message: "Approval request timed out"
      then: CheckApprovalOutcome
  - CheckApprovalOutcome:
      switch:
        - case1:
            then: HandleApproval
            when: ${ .events.ApprovalEvent != null }
        - default:
            then: HandleTimeout
```

### Event-Based Circuit Breakers

You can implement circuit breaker patterns:

```yaml
  - MonitorServiceHealth:
      try:
        - MonitorServiceHealthEvents:
            listen:
              to:
                any:
                  - with:
                      type: "ServiceError"
                    correlate:
                      serviceId:
                        from: ${ .data.serviceId }
                        expect: ${ .serviceId }
                until: ${ length >= 5 }
            timeout:
              after: PT1M
      catch:
        errors:
          with:
            status: 408
        then: ServiceHealthy
      then: TripCircuitBreaker
  - TripCircuitBreaker:
      emit:
        event:
          with:
            type: "CircuitBreakerTripped"
            data:
              serviceId: ${ .serviceId }
              errorCount: ${ .events.ErrorEvents | length }
              timestamp: ${ $context.currentTime }
      then: WaitForRecovery
  - WaitForRecovery:
      wait: PT5M
      then: ResetCircuitBreaker
```

## Real-World Example: Multi-Step Approval Process

Here's a complete example of an event-driven approval workflow:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: approval-workflow
  version: 1.0.0
  title: Multi-step Approval Process
do:
  - InitiateApproval:
      set:
        requestId: ${ $workflow.input.requestId }
        requestType: ${ $workflow.input.requestType }
        requestorId: ${ $workflow.input.requestorId }
        amount: ${ $workflow.input.amount }
        description: ${ $workflow.input.description }
        approvalStatus: "PENDING"
        requiredApprovals: 2
        approvals: []
        rejections: []
      then: NotifyApprovers
  - NotifyApprovers:
      emit:
        event:
          with:
            type: "ApprovalRequested"
            data:
              requestId: ${ .requestId }
              requestType: ${ .requestType }
              requestorId: ${ .requestorId }
              amount: ${ .amount }
              description: ${ .description }
              requiredApprovals: ${ .requiredApprovals }
      then: WaitForApprovals
  - WaitForApprovals:
      try:
        - WaitForApprovalsEvents:
            listen:
              to:
                any:
                  - with:
                      type: "ApprovalResponse"
                    correlate:
                      requestId:
                        from: ${ .data.requestId }
                        expect: ${ .requestId }
                until: ${ (.events.ApprovalResponses | map(select(.decision == "APPROVED")) | length < .requiredApprovals and .events.ApprovalResponses | map(select(.decision == "REJECTED")) | length == 0) | not }
            timeout:
              after: PT72H
      catch:
        errors:
          with:
            status: 408
        then: HandleApprovalTimeout
      then: EvaluateApprovalStatus
  - ProcessApprovalResponse:
      set:
        currentResponse: ${ .response }
        approverName: ${ .currentResponse.approverName }
        decision: ${ .currentResponse.decision }
        comments: ${ .currentResponse.comments }
        approvals: ${ if .decision == "APPROVED" then .approvals + [.currentResponse] else .approvals end }
        rejections: ${ if .decision == "REJECTED" then .rejections + [.currentResponse] else .rejections end }
      then: EmitResponseReceived
  - EmitResponseReceived:
      emit:
        event:
          with:
            type: "ApprovalResponseReceived"
            data:
              requestId: ${ .requestId }
              approverName: ${ .approverName }
              decision: ${ .decision }
              comments: ${ .comments }
              timestamp: ${ $context.currentTime }
              approvalCount: ${ .approvals | length }
              rejectionCount: ${ .rejections | length }
  - EvaluateApprovalStatus:
      set:
        allResponses: ${ .events.ApprovalResponses }
        approvalCount: ${ .approvals | length }
        rejectionCount: ${ .rejections | length }
        finalStatus: ${ if .rejectionCount > 0 then "REJECTED" else if .approvalCount >= .requiredApprovals then "APPROVED" else "PENDING" end end }
      then: FinalizeApproval
  - FinalizeApproval:
      switch:
        - case1:
            then: HandleApproved
            when: ${ .finalStatus == "APPROVED" }
        - case2:
            then: HandleRejected
            when: ${ .finalStatus == "REJECTED" }
        - default:
            then: HandlePending
  - HandleApproved:
      emit:
        event:
          with:
            type: "RequestApproved"
            data:
              requestId: ${ .requestId }
              requestType: ${ .requestType }
              requestorId: ${ .requestorId }
              amount: ${ .amount }
              approvers: ${ .approvals | map(.approverName) }
              timestamp: ${ $context.currentTime }
      then: CompleteApproved
  - CompleteApproved:
      set:
        status: "APPROVED"
        message: ${ "Request approved by \(.approvalCount) approvers" }
        approvers: ${ .approvals | map(.approverName) }
      then: end
  - HandleRejected:
      emit:
        event:
          with:
            type: "RequestRejected"
            data:
              requestId: ${ .requestId }
              requestType: ${ .requestType }
              requestorId: ${ .requestorId }
              rejectedBy: ${ .rejections[0].approverName }
              reason: ${ .rejections[0].comments }
              timestamp: ${ $context.currentTime }
      then: CompleteRejected
  - CompleteRejected:
      set:
        status: "REJECTED"
        message: ${ "Request rejected by \(.rejections)[0]\(.approverName)" }
        reason: ${ .rejections[0].comments }
      then: end
  - HandlePending:
      set:
        status: "PENDING"
        message: "Request is still pending approval"
        currentApprovals: ${ .approvalCount }
        requiredApprovals: ${ .requiredApprovals }
      then: end
  - HandleApprovalTimeout:
      emit:
        event:
          with:
            type: "ApprovalTimeout"
            data:
              requestId: ${ .requestId }
              requestType: ${ .requestType }
              requestorId: ${ .requestorId }
              timestamp: ${ $context.currentTime }
              currentApprovals: ${ .approvals | length }
              requiredApprovals: ${ .requiredApprovals }
      then: CompleteTimeout
  - CompleteTimeout:
      set:
        status: "TIMEOUT"
        message: "Request timed out after 72 hours"
        currentApprovals: ${ .approvals | length }
        requiredApprovals: ${ .requiredApprovals }
      then: end
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
  - ProcessCommand:
      emit:
        event:
          with:
            type: "UpdateInventory"
            data:
              productId: ${ .productId }
              quantityChange: ${ .quantityChange }
              operation: ${ .operation }
      then: WaitForCompletion
  - WaitForCompletion:
      listen:
        to:
          one:
            with:
              type: "InventoryUpdateCompleted"
            correlate:
              requestId:
                from: ${ .data.requestId }
                expect: ${ .requestId }
      then: QueryCurrentState
  - QueryCurrentState:
      call: inventoryService
      with:
        productId: ${ .productId }
      then: ProcessResult
```

### Event Sourcing

Use events as the system of record:

```yaml
  - RecordEvent:
      emit:
        event:
          with:
            type: "OrderStateChanged"
            data:
              orderId: ${ .orderId }
              previousState: ${ .currentState }
              newState: ${ .newState }
              reason: ${ .reason }
              timestamp: ${ $context.currentTime }
      then: UpdateLocalState
  - UpdateLocalState:
      set:
        currentState: ${ .newState }
        stateHistory: '${ .stateHistory + [{state: .newState, timestamp: $context.currentTime, reason: .reason}] }'
      then: NextTask
```

### Saga Pattern

Coordinate distributed transactions with compensation:

```yaml
  - StartTransaction:
      emit:
        event:
          with:
            type: "PaymentRequested"
            data:
              transactionId: ${ .transactionId }
              amount: ${ .amount }
              customerId: ${ .customerId }
      then: WaitForPaymentResult
  - WaitForPaymentResult:
      listen:
        to:
          one:
            with:
              type: "PaymentProcessed"
            correlate:
              transactionId:
                from: ${ .data.transactionId }
                expect: ${ .transactionId }
      then: CheckPaymentSuccess
  - CheckPaymentSuccess:
      switch:
        - case1:
            then: RequestInventoryReservation
            when: ${ .events.PaymentResult[0].success == true }
        - default:
            then: HandlePaymentFailure
  - RequestInventoryReservation:
      emit:
        event:
          with:
            type: "InventoryReservationRequested"
            data:
              transactionId: ${ .transactionId }
              items: ${ .items }
      then: WaitForReservationResult
  - WaitForReservationResult:
      listen:
        to:
          one:
            with:
              type: "InventoryReserved"
            correlate:
              transactionId:
                from: ${ .data.transactionId }
                expect: ${ .transactionId }
      then: CheckReservationSuccess
  - CheckReservationSuccess:
      switch:
        - case1:
            then: FinalizeTransaction
            when: ${ .events.ReservationResult[0].success == true }
        - default:
            then: CompensatePayment
  - CompensatePayment:
      emit:
        event:
          with:
            type: "PaymentReversal"
            data:
              transactionId: ${ .transactionId }
              reason: "Inventory reservation failed"
      then: HandleTransactionFailure
```

## Next Steps

- Learn about [data passing between tasks](lemline-howto-data-passing.md)
- Explore [runtime expressions and jq](lemline-howto-jq.md)
- Understand [error propagation and resilience](lemline-explain-errors.md)