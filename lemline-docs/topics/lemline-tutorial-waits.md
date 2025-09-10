---
title: "Tutorial: Waits, Fan-In, and Timers"
---

# Tutorial: Waits, Fan-In, and Timers

This tutorial explores the time-based and event correlation features in Lemline. You'll learn how to implement waiting periods, correlate events from multiple sources, and handle timeouts in your workflows.

## Learning Objectives

By completing this tutorial, you will learn:

- How to implement time-based waits in workflows
- How to set up event listeners for single and multiple events
- How to correlate events from different sources (fan-in pattern)
- How to implement timeout handling strategies
- How Lemline persists state for time-dependent operations

## Prerequisites

- Completion of the [Hello, Workflow!](lemline-tutorial-hello.md) tutorial
- Basic understanding of event-driven architecture concepts
- Lemline runtime installed and configured

## 1. Understanding Time-Based Operations

When workflows need to wait for specific durations or external events, state persistence becomes necessary. Lemline optimizes this by:

- Using a specialized wait table for time-based operations
- Implementing an efficient outbox pattern for reliability
- Minimizing persistence to only what's needed for resumability

## 2. Setting Up Your Project

Create a new directory for this tutorial:

```bash
mkdir wait-patterns
cd wait-patterns
```

## 3. Implementing Duration-Based Waits

First, let's create a simple workflow that demonstrates duration-based waiting. Create a file named `duration-wait.yaml`:

```yaml
id: duration-wait
name: Duration Wait Example
version: '1.0'
specVersion: '1.0'
start: StartProcess
functions:
  - name: logFunction
    type: custom
    operation: log
tasks:
  - name: StartProcess
    type: set
    data:
      startTime: "$WORKFLOW.startTime"
      message: "Starting process"
    next: LogStart
  
  - name: LogStart
    type: call
    function: logFunction
    data:
      log: ".message"
    next: WaitTwoMinutes
  
  - name: WaitTwoMinutes
    type: wait
    data:
      duration: PT2M
    next: ProcessAfterWait
  
  - name: ProcessAfterWait
    type: set
    data:
      endTime: "$WORKFLOW.startTime"
      waitDuration: "date.difference(.startTime, .endTime, 'seconds')"
      message: "Process resumed after waiting for .waitDuration seconds"
    next: LogCompletion
  
  - name: LogCompletion
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
```

The `wait` task with a `PT2M` duration instructs the workflow to pause execution for 2 minutes before continuing. The duration format follows ISO 8601 duration format.

Run this workflow:

```bash
java -jar lemline-runner.jar workflow run duration-wait.yaml
```

The workflow will output:
1. "Starting process"
2. Wait for 2 minutes
3. "Process resumed after waiting for X seconds"

## 4. Implementing Event-Based Waits

Next, let's create a workflow that waits for a specific event. Create a file named `event-wait.yaml`:

```yaml
id: event-wait
name: Event Wait Example
version: '1.0'
specVersion: '1.0'
start: InitializeOrder
functions:
  - name: logFunction
    type: custom
    operation: log
tasks:
  - name: InitializeOrder
    type: set
    data:
      orderId: "ORD-12345"
      status: "PENDING"
      message: "Order ORD-12345 initialized and waiting for payment confirmation"
    next: LogInitialization
  
  - name: LogInitialization
    type: call
    function: logFunction
    data:
      log: ".message"
    next: WaitForPayment
  
  - name: WaitForPayment
    type: listen
    events:
      - name: PaymentReceived
        type: PaymentReceived
        correlations:
          - orderId = .orderId
        consume: 1
        timeouts:
          eventTimeout: PT30M
          eventTimeoutNext: HandlePaymentTimeout
    next: ProcessOrder
  
  - name: HandlePaymentTimeout
    type: set
    data:
      status: "CANCELLED"
      message: "Order .orderId cancelled due to payment timeout"
    next: LogCancellation
  
  - name: LogCancellation
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
  
  - name: ProcessOrder
    type: set
    data:
      status: "PROCESSING"
      message: "Order .orderId payment received, processing order"
    next: LogProcessing
  
  - name: LogProcessing
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
```

The `listen` task waits for a `PaymentReceived` event with a matching `orderId`. If the event doesn't arrive within 30 minutes, it times out and executes the `HandlePaymentTimeout` task.

To test this, you'll need to:
1. Start the workflow in one terminal
2. Send a matching event in another terminal

Run the workflow:

```bash
java -jar lemline-runner.jar workflow run event-wait.yaml
```

In another terminal, send a matching event:

```bash
java -jar lemline-runner.jar events publish PaymentReceived '{"orderId": "ORD-12345", "amount": 100.00, "transactionId": "TX-789"}'
```

## 5. Implementing Fan-In Pattern

Now, let's create a more complex workflow that demonstrates the fan-in pattern, where we wait for multiple events from different sources. Create a file named `fan-in.yaml`:

```yaml
id: room-temperature-monitor
name: Room Temperature Monitoring
version: '1.0'
specVersion: '1.0'
start: InitializeMonitoring
functions:
  - name: logFunction
    type: custom
    operation: log
  - name: notifyFunction
    type: custom
    operation: notify
tasks:
  - name: InitializeMonitoring
    type: set
    data:
      roomId: "ROOM-123"
      readingThreshold: 3
      message: "Initializing temperature monitoring for room ROOM-123"
    next: LogInitialization
  
  - name: LogInitialization
    type: call
    function: logFunction
    data:
      log: ".message"
    next: CollectReadings
  
  - name: CollectReadings
    type: listen
    events:
      - name: TemperatureReadings
        type: TemperatureReading
        correlations:
          - roomId = .roomId
        consume: 3
        timeouts:
          eventTimeout: PT10M
          eventTimeoutNext: HandleInsufficientReadings
    next: AnalyzeReadings
  
  - name: HandleInsufficientReadings
    type: set
    data:
      status: "INCOMPLETE"
      message: "Insufficient readings collected for room .roomId within the time window"
    next: LogInsufficientReadings
  
  - name: LogInsufficientReadings
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
  
  - name: AnalyzeReadings
    type: set
    data:
      readings: ".events.TemperatureReadings"
      averageTemperature: "[ .readings[].temperature ] | add / length"
      status: ".averageTemperature > 25 ? 'TOO_HOT' : (.averageTemperature < 18 ? 'TOO_COLD' : 'NORMAL')"
      message: "Average temperature for room .roomId is .averageTemperature degrees (.status)"
    next: LogAnalysis
  
  - name: LogAnalysis
    type: call
    function: logFunction
    data:
      log: ".message"
    next: CheckTemperatureStatus
  
  - name: CheckTemperatureStatus
    type: switch
    conditions:
      - condition: ".status == 'NORMAL'"
        next: End
      - condition: true
        next: SendAlert
  
  - name: SendAlert
    type: call
    function: notifyFunction
    data:
      to: "facilities@example.com"
      subject: "Temperature Alert for Room .roomId"
      message: "Room .roomId temperature is .averageTemperature degrees (.status). Please investigate."
    end: true
  
  - name: End
    type: set
    data:
      message: "Monitoring complete for room .roomId"
    end: true
```

This workflow:
1. Waits for 3 temperature reading events from the same room
2. Calculates the average temperature once all readings are received
3. Sends an alert if the temperature is outside the acceptable range
4. Times out if insufficient readings are received within 10 minutes

To test this, run the workflow:

```bash
java -jar lemline-runner.jar workflow run fan-in.yaml
```

Then send 3 temperature reading events:

```bash
java -jar lemline-runner.jar events publish TemperatureReading '{"roomId": "ROOM-123", "temperature": 22.5, "sensorId": "SENSOR-1", "timestamp": "2023-09-10T14:30:00Z"}'

java -jar lemline-runner.jar events publish TemperatureReading '{"roomId": "ROOM-123", "temperature": 23.1, "sensorId": "SENSOR-2", "timestamp": "2023-09-10T14:31:00Z"}'

java -jar lemline-runner.jar events publish TemperatureReading '{"roomId": "ROOM-123", "temperature": 21.8, "sensorId": "SENSOR-3", "timestamp": "2023-09-10T14:32:00Z"}'
```

## 6. Understanding Wait State Persistence

When a workflow reaches a `wait` or `listen` task, Lemline needs to persist some state to enable resuming the workflow later. Let's examine how this works:

1. **Wait Table**: When a workflow enters a wait state, Lemline writes a record to the wait table with:
   - Workflow ID
   - Node position
   - Wake-up time (for duration-based waits)
   - Event correlation criteria (for event-based waits)
   - Event consumption count (for fan-in patterns)

2. **Outbox Pattern**: For reliability, Lemline uses the outbox pattern:
   - Wait records are inserted in the same transaction as other workflow operations
   - A separate outbox processor reliably processes wait records
   - This ensures exactly-once semantics for resuming workflows

3. **Minimal State**: Only essential information needed for resumability is stored:
   - Workflow instance ID
   - Current node position
   - Correlation criteria
   - Timeout information

4. **Wake-up Mechanism**: Lemline periodically scans the wait table to:
   - Identify expired durations
   - Match received events against correlation criteria
   - Resume workflows when conditions are met

## 7. Implementing Advanced Patterns

Now let's combine these concepts into a more sophisticated workflow. Create a file named `advanced-wait.yaml`:

```yaml
id: approval-process
name: Multi-Step Approval Process
version: '1.0'
specVersion: '1.0'
start: InitiateApproval
functions:
  - name: logFunction
    type: custom
    operation: log
  - name: notifyFunction
    type: custom
    operation: notify
tasks:
  - name: InitiateApproval
    type: set
    data:
      requestId: "REQ-789"
      requester: "john.doe@example.com"
      amount: 5000
      approversNeeded: 2
      message: "Initiated approval request REQ-789 for $5000"
    next: LogInitiation
  
  - name: LogInitiation
    type: call
    function: logFunction
    data:
      log: ".message"
    next: NotifyApprovers
  
  - name: NotifyApprovers
    type: call
    function: notifyFunction
    data:
      to: ["manager@example.com", "finance@example.com", "director@example.com"]
      subject: "Approval Required for Request .requestId"
      message: "Approval is required for expense request .requestId for $amount submitted by .requester"
    next: WaitForApprovals
  
  - name: WaitForApprovals
    type: listen
    events:
      - name: ApprovalResponses
        type: ApprovalResponse
        correlations:
          - requestId = .requestId
        consume: 2
        timeouts:
          eventTimeout: PT48H
          eventTimeoutNext: HandleApprovalTimeout
    next: CheckAllApproved
  
  - name: HandleApprovalTimeout
    type: set
    data:
      status: "EXPIRED"
      message: "Approval request .requestId expired due to insufficient responses within 48 hours"
    next: NotifyRequesterOfTimeout
  
  - name: NotifyRequesterOfTimeout
    type: call
    function: notifyFunction
    data:
      to: ".requester"
      subject: "Approval Request .requestId Expired"
      message: "Your approval request .requestId has expired due to insufficient responses within the required timeframe."
    end: true
  
  - name: CheckAllApproved
    type: set
    data:
      approvals: ".events.ApprovalResponses"
      approvedCount: "[.approvals[] | select(.approved == true)] | length"
      allApproved: ".approvedCount >= .approversNeeded"
      message: "Received .approvedCount approvals out of .approversNeeded required"
    next: LogApprovalStatus
  
  - name: LogApprovalStatus
    type: call
    function: logFunction
    data:
      log: ".message"
    next: CheckApprovalDecision
  
  - name: CheckApprovalDecision
    type: switch
    conditions:
      - condition: ".allApproved == true"
        next: ProcessApprovedRequest
      - condition: true
        next: HandleRejectedRequest
  
  - name: ProcessApprovedRequest
    type: set
    data:
      status: "APPROVED"
      message: "Request .requestId approved"
    next: NotifyRequesterOfApproval
  
  - name: NotifyRequesterOfApproval
    type: call
    function: notifyFunction
    data:
      to: ".requester"
      subject: "Request .requestId Approved"
      message: "Your request .requestId for $amount has been approved."
    next: WaitForDisbursement
  
  - name: WaitForDisbursement
    type: wait
    data:
      duration: PT24H
    next: VerifyDisbursement
  
  - name: VerifyDisbursement
    type: set
    data:
      message: "Following up on disbursement for approved request .requestId"
    next: LogDisbursementFollowUp
  
  - name: LogDisbursementFollowUp
    type: call
    function: logFunction
    data:
      log: ".message"
    end: true
  
  - name: HandleRejectedRequest
    type: set
    data:
      status: "REJECTED"
      message: "Request .requestId rejected"
    next: NotifyRequesterOfRejection
  
  - name: NotifyRequesterOfRejection
    type: call
    function: notifyFunction
    data:
      to: ".requester"
      subject: "Request .requestId Rejected"
      message: "Your request .requestId for $amount has been rejected."
    end: true
```

This workflow demonstrates:
1. Fan-in pattern: Waiting for approvals from multiple sources
2. Event correlation: Matching events by requestId
3. Duration-based wait: Following up on disbursement after 24 hours
4. Timeout handling: Expiring the request if insufficient approvals arrive within 48 hours

## What You've Learned

In this tutorial, you've learned how to:

- Implement time-based waits using the `wait` task
- Listen for events using the `listen` task
- Correlate multiple events using the fan-in pattern
- Implement timeout handling for events that don't arrive
- Understand how Lemline persists state for time-dependent operations

## Next Steps

To further explore Lemline's capabilities:

- Learn about [Streaming Sensor Events (IoT)](lemline-tutorial-iot.md) for more advanced event processing
- Explore how to [handle errors gracefully](lemline-howto-try-catch.md) in workflows
- Understand [how Lemline handles time](lemline-explain-time.md) at a deeper level
- Study the [Inside a Fan-In](lemline-explain-fan-in.md) explanation to understand correlation mechanics