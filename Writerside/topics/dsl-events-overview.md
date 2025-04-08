---
title: Event Tasks
---

Event tasks enable workflows to interact with event-driven systems, allowing workflows to listen for external events and emit events to other systems, facilitating asynchronous and reactive patterns in your applications.

## Common Use Cases

Leveraging events in your workflows unlocks several powerful patterns:

* **Waiting for External Triggers:** A workflow can start or resume based on an external event, such as a new file
  arriving in storage, a message landing on a queue, or a webhook notification. (Often implemented using `Listen` or
  platform-specific triggers).
* **Reacting to System Changes:** Workflows can listen for events indicating changes in other systems (e.g., inventory
  updates, user profile changes) and trigger appropriate actions.
* **Asynchronous Task Completion:** A workflow can initiate a long-running operation via a call task and then use
  `Listen` to wait for a completion event from that operation, rather than blocking synchronously.
* **Inter-Workflow Communication:** One workflow can `Emit` an event that triggers or provides data to another workflow
  instance via `Listen`.
* **Saga Pattern / Compensating Transactions:** Events can signal the success or failure of steps in a distributed
  transaction, allowing other services or workflows to react and perform compensating actions if necessary.
* **Decoupled Integration:** Services can communicate via events without needing direct knowledge of each other,
  promoting loose coupling and independent evolution.


## Available Event Tasks

| Task | Purpose |
|------|---------|
| [Listen](dsl-task-listen.md) | Wait for events from external sources and optionally filter them |
| [Emit](dsl-task-emit.md) | Send events to external systems or trigger other workflows |

## When to Use Event Tasks

- Use **Listen** when you need to:
  - Start or resume workflows based on external events
  - Wait for specific conditions signaled by events
  - Implement event-driven patterns like event sourcing
  - Build reactive workflows that respond to system changes
  - Coordinate long-running processes across distributed systems

- Use **Emit** when you need to:
  - Notify other systems about workflow state changes
  - Trigger parallel workflows or microservices
  - Implement pub/sub patterns for loose coupling
  - Broadcast completion or progress updates
  - Signal transitions in business processes

## Examples

### Order Processing with Event Handling

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: event-driven-order-processing
  version: '1.0.0'
use:
  events:
    orderCreated:
      source: order-service
      type: com.example.order.created
      dataSchema:
        type: object
        properties:
          orderId:
            type: string
          customerId:
            type: string
          items:
            type: array
            items:
              type: object
              properties:
                productId: 
                  type: string
                quantity:
                  type: integer
          totalAmount:
            type: number
    paymentProcessed:
      source: payment-service
      type: com.example.payment.processed
      dataSchema:
        type: object
        properties:
          orderId:
            type: string
          paymentId:
            type: string
          status:
            type: string
            enum: [SUCCESS, FAILED]
          amount:
            type: number
    orderConfirmed:
      source: order-system
      type: com.example.order.confirmed
      dataSchema:
        type: object
        properties:
          orderId:
            type: string
          status:
            type: string
  functions:
    reserveInventory:
      operation:
        serviceType: rest
        endpoint: https://api.example.com/inventory/reserve
        method: POST
do:
  - waitForNewOrder:
      listen:
        event: orderCreated
        result: newOrder
        
  - reserveInventoryItems:
      call:
        function: reserveInventory
        args:
          orderId: ${ .newOrder.data.orderId }
          items: ${ .newOrder.data.items }
        result: inventoryReservation
        
  - waitForPayment:
      listen:
        event: paymentProcessed
        eventFilter:
          correlate:
            orderId:
              from: ${ .data.orderId }
              expect: ${ .newOrder.data.orderId }
        timeout: PT1H
        result: paymentEvent
        
  - finalizeOrder:
      switch:
        condition: ${ .paymentEvent.data.status }
        cases:
          - value: SUCCESS
            do:
              - confirmOrder:
                  emit:
                    event: orderConfirmed
                    data:
                      orderId: ${ .newOrder.data.orderId }
                      status: CONFIRMED
                      paymentId: ${ .paymentEvent.data.paymentId }
                      confirmedAt: ${ new Date().toISOString() }
        default:
          - handleFailedPayment:
              emit:
                event: orderConfirmed
                data:
                  orderId: ${ .newOrder.data.orderId }
                  status: PAYMENT_FAILED
                  reason: "Payment processing failed"
                  failedAt: ${ new Date().toISOString() }
```

### Event-Based Microservice Coordination

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: microservice-coordination
  version: '1.0.0'
use:
  events:
    userRegistered:
      source: user-service
      type: com.example.user.registered
    welcomeEmailSent:
      source: email-service
      type: com.example.email.sent
    userProfileCreated:
      source: profile-service
      type: com.example.profile.created
    onboardingCompleted:
      source: onboarding-service
      type: com.example.onboarding.completed
  functions:
    createUserProfile:
      operation:
        serviceType: rest
        endpoint: https://api.example.com/profiles
        method: POST
    sendWelcomeEmail:
      operation:
        serviceType: rest
        endpoint: https://api.example.com/emails/welcome
        method: POST
do:
  - waitForNewUser:
      listen:
        event: userRegistered
        result: newUser
        
  - processNewUser:
      parallel:
        branches:
          - createProfile:
              do:
                - initiateProfileCreation:
                    call:
                      function: createUserProfile
                      args:
                        userId: ${ .newUser.data.userId }
                        email: ${ .newUser.data.email }
                        username: ${ .newUser.data.username }
                      result: profileCreationResult
                      
                - notifyProfileCreated:
                    emit:
                      event: userProfileCreated
                      data:
                        userId: ${ .newUser.data.userId }
                        profileId: ${ .profileCreationResult.profileId }
                        createdAt: ${ new Date().toISOString() }
                
          - sendWelcome:
              do:
                - initiateWelcomeEmail:
                    call:
                      function: sendWelcomeEmail
                      args:
                        to: ${ .newUser.data.email }
                        name: ${ .newUser.data.firstName }
                        language: ${ .newUser.data.preferences.language || "en" }
                      result: emailResult
                      
                - notifyEmailSent:
                    emit:
                      event: welcomeEmailSent
                      data:
                        userId: ${ .newUser.data.userId }
                        emailId: ${ .emailResult.emailId }
                        sentAt: ${ new Date().toISOString() }
                
  - completeOnboarding:
      listen:
        all:
          - event: userProfileCreated
            eventFilter:
              correlate:
                userId:
                  from: ${ .data.userId }
                  expect: ${ .newUser.data.userId }
                
          - event: welcomeEmailSent
            eventFilter:
              correlate:
                userId:
                  from: ${ .data.userId }
                  expect: ${ .newUser.data.userId }
        timeout: PT30M
        result: onboardingEvents
        
  - notifyOnboardingCompletion:
      emit:
        event: onboardingCompleted
        data:
          userId: ${ .newUser.data.userId }
          status: "COMPLETE"
          profileId: ${ .onboardingEvents[0].data.profileId }
          emailSent: true
          completedAt: ${ new Date().toISOString() }
```

Event tasks form the foundation of event-driven architecture within workflows, enabling responsive, loosely-coupled systems that can react to changes across distributed environments. By using Listen and Emit tasks appropriately, workflows can participate in complex event ecosystems, coordinating business processes that span multiple services while maintaining resilience and scalability. 