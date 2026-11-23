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

Work in the `lemline-project` directory from [Setting Up a Local Environment](lemline-tutorial-setup.md). Keep the worker running with the `examples` namespace enabled.

## 3. Implementing Duration-Based Waits

First, let's create a simple workflow that demonstrates duration-based waiting. Create a file named `duration-wait.yaml`:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: duration-wait
  version: 1.0.0
  title: Duration Wait Example
do:
  - recordStart:
      set: { started: '${ now }' }
      export: { as: '${ $context + . }' }
  - waitTwoMinutes:
      wait: PT2M
  - reportDuration:
      set:
        elapsedSeconds: ${ now - $context.started | floor }
        message: Process resumed after the wait
```

The `wait` task with a `PT2M` duration instructs the workflow to pause execution for 2 minutes before continuing. The duration format follows ISO 8601 duration format.

Run this workflow:

```bash
bin/lemline definition post -f duration-wait.yaml
bin/lemline instance start examples duration-wait
```

After two minutes the workflow returns its elapsed time in seconds and the completion message.

## 4. Implementing Event-Based Waits

Next, let's create a workflow that waits for a specific event. Create a file named `event-wait.yaml`:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: event-wait
  version: 1.0.0
  title: Event Wait Example
do:
  - waitForPayment:
      try:
        - receivePayment:
            listen:
              to:
                one:
                  with: { type: PaymentReceived }
                  correlate:
                    orderId:
                      from: ${ .data.orderId }
                      expect: ${ $workflow.input.orderId }
              read: data
            timeout: { after: PT30M }
      catch:
        errors: { with: { status: 408 } }
        do:
          - HandlePaymentTimeout:
              set:
                orderId: ${ $workflow.input.orderId }
                status: CANCELLED
              then: end
  - processOrder:
      set:
        orderId: ${ .orderId }
        status: PROCESSING
```

The `listen` task waits for a `PaymentReceived` event with a matching `orderId`. If the event doesn't arrive within 30 minutes, it times out and executes the `HandlePaymentTimeout` task.

To test this, you'll need to:
1. Start the workflow in one terminal
2. Send a matching event in another terminal

Run the workflow:

```bash
bin/lemline definition post -f event-wait.yaml
bin/lemline instance start examples event-wait --input '{"orderId":"ORD-12345"}'
```

In another terminal, send a matching event:

Save the following helper as `publish-event.yaml`. Each instance emits one CloudEvent in the tutorial namespace.

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: publish-event
  version: '1.0.0'
do:
  - publish:
      emit:
        event:
          with:
            source: https://example.com/tutorial
            type: ${ .type }
            data: ${ .data }
```

```bash
bin/lemline definition post -f publish-event.yaml
```

```bash
bin/lemline instance start examples publish-event --input '{"type": "PaymentReceived", "data": {"orderId": "ORD-12345", "amount": 100.0, "transactionId": "TX-789"}}'
```

## 5. Implementing Fan-In Pattern

Now, let's create a more complex workflow that demonstrates the fan-in pattern, where we wait for multiple events from different sources. Create a file named `fan-in.yaml`:

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: room-temperature-monitor
  version: 1.0.0
  title: Room Temperature Monitoring
do:
  - collect:
      try:
        - receiveReadings:
            listen:
              to:
                any:
                  - with: { type: TemperatureReading }
                    correlate:
                      roomId:
                        from: ${ .data.roomId }
                        expect: ${ $workflow.input.roomId }
                until: ${ length >= 3 }
              read: data
            timeout: { after: PT10M }
      catch:
        errors: { with: { status: 408 } }
        do:
          - reportMissingReadings:
              set: { status: INCOMPLETE, roomId: '${ $workflow.input.roomId }' }
              then: end
  - average:
      set:
        roomId: ${ $workflow.input.roomId }
        averageTemperature: ${ map(.temperature) | add / length }
  - sendAlert:
      if: ${ .averageTemperature < 18 or .averageTemperature > 25 }
      emit:
        event:
          with:
            source: https://example.com/tutorial
            type: RoomTemperatureAlert
            data: ${ . }
```

This workflow:
1. Waits for 3 temperature reading events from the same room
2. Calculates the average temperature once all readings are received
3. Sends an alert if the temperature is outside the acceptable range
4. Times out if insufficient readings are received within 10 minutes

To test this, run the workflow:

```bash
bin/lemline definition post -f fan-in.yaml
bin/lemline instance start examples room-temperature-monitor --input '{"roomId":"ROOM-123"}'
```

Then send 3 temperature reading events:

```bash
bin/lemline instance start examples publish-event --input '{"type": "TemperatureReading", "data": {"roomId": "ROOM-123", "temperature": 22.5, "sensorId": "SENSOR-1", "timestamp": "2023-09-10T14:30:00Z"}}'

bin/lemline instance start examples publish-event --input '{"type": "TemperatureReading", "data": {"roomId": "ROOM-123", "temperature": 23.1, "sensorId": "SENSOR-2", "timestamp": "2023-09-10T14:31:00Z"}}'

bin/lemline instance start examples publish-event --input '{"type": "TemperatureReading", "data": {"roomId": "ROOM-123", "temperature": 21.8, "sensorId": "SENSOR-3", "timestamp": "2023-09-10T14:32:00Z"}}'
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
document:
  dsl: 1.0.3
  namespace: examples
  name: approval-process
  version: 1.0.0
  title: Multi-Step Approval Process
do:
  - InitiateApproval:
      set:
        requestId: "REQ-789"
        requester: "john.doe@example.com"
        amount: 5000
        approversNeeded: 2
        message: "Initiated approval request REQ-789 for $5000"
      then: LogInitiation
  - LogInitiation:
      call: https://raw.githubusercontent.com/open-workflow-specification/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
      then: NotifyApprovers
  - NotifyApprovers:
      call: notifyFunction
      with:
        to:
          - "manager@example.com"
          - "finance@example.com"
          - "director@example.com"
        subject: ${ "Approval Required for Request \(.requestId)" }
        message: ${ "Approval is required for expense request \(.requestId) for $amount submitted by \(.requester)" }
      then: WaitForApprovals
  - WaitForApprovals:
      try:
        - WaitForApprovalsEvents:
            listen:
              to:
                any:
                  - with:
                      type: ApprovalResponse
                    correlate:
                      requestId:
                        from: ${ .data.requestId }
                        expect: ${ .requestId }
                until: ${ length >= 2 }
            timeout:
              after: PT48H
      catch:
        errors:
          with:
            status: 408
        then: HandleApprovalTimeout
      then: CheckAllApproved
  - HandleApprovalTimeout:
      set:
        status: "EXPIRED"
        message: ${ "Approval request \(.requestId) expired due to insufficient responses within 48 hours" }
      then: NotifyRequesterOfTimeout
  - NotifyRequesterOfTimeout:
      call: notifyFunction
      with:
        to: ${ .requester }
        subject: ${ "Approval Request \(.requestId) Expired" }
        message: ${ "Your approval request \(.requestId) has expired due to insufficient responses within the required timeframe." }
      then: end
  - CheckAllApproved:
      set:
        approvals: ${ .events.ApprovalResponses }
        approvedCount: ${ [.approvals[] | select(.approved == true)] | length }
        allApproved: ${ .approvedCount >= .approversNeeded }
        message: ${ "Received \(.approvedCount) approvals out of \(.approversNeeded) required" }
      then: LogApprovalStatus
  - LogApprovalStatus:
      call: https://raw.githubusercontent.com/open-workflow-specification/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
      then: CheckApprovalDecision
  - CheckApprovalDecision:
      switch:
        - case1:
            then: ProcessApprovedRequest
            when: ${ .allApproved == true }
        - default:
            then: HandleRejectedRequest
  - ProcessApprovedRequest:
      set:
        status: "APPROVED"
        message: ${ "Request \(.requestId) approved" }
      then: NotifyRequesterOfApproval
  - NotifyRequesterOfApproval:
      call: notifyFunction
      with:
        to: ${ .requester }
        subject: ${ "Request \(.requestId) Approved" }
        message: ${ "Your request \(.requestId) for $amount has been approved." }
      then: WaitForDisbursement
  - WaitForDisbursement:
      wait: PT24H
      then: VerifyDisbursement
  - VerifyDisbursement:
      set:
        message: ${ "Following up on disbursement for approved request \(.requestId)" }
      then: LogDisbursementFollowUp
  - LogDisbursementFollowUp:
      call: https://raw.githubusercontent.com/open-workflow-specification/catalog/main/functions/log/1.0.0/function.yaml
      with:
        message: ${ .message }
      then: end
  - HandleRejectedRequest:
      set:
        status: "REJECTED"
        message: ${ "Request \(.requestId) rejected" }
      then: NotifyRequesterOfRejection
  - NotifyRequesterOfRejection:
      call: notifyFunction
      with:
        to: ${ .requester }
        subject: ${ "Request \(.requestId) Rejected" }
        message: ${ "Your request \(.requestId) for $amount has been rejected." }
      then: end
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