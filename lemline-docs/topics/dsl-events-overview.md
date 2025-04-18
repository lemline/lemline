---
title: Event Tasks
---

<!-- Examples are validated -->


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
  functions:
    reserveInventory:
      call: function
      with:
        function: reserveInventory
        args:
          orderId: ${ .orderId }
          items: ${ .items }
do:
  - waitForNewOrder:
      listen:
        to:
          one:
            with:
              source: "http://order-service"
              type: "com.example.order.created"
        read: "data"
        
  - reserveInventoryItems:
      call: "reserveInventory"
      with:
        args:
          orderId: ${ .waitForNewOrder.data.orderId }
          items: ${ .waitForNewOrder.data.items }
        
  - waitForPayment:
      listen:
        to:
          one:
            with:
              source: "http://payment-service"
              type: "com.example.payment.processed"
            correlate:
              orderId:
                from: ${ .data.orderId }
                expect: ${ .waitForNewOrder.data.orderId }
        read: "data"
      timeout:
        after: PT1H
        
  - checkPaymentStatus:
      switch:
        - case:
            when: ${ .waitForPayment.data.status == "SUCCESS" }
            then: "confirmOrder"
        - default:
            then: "handleFailedPayment"
            
  - confirmOrder:
      emit:
        event:
          with:
            source: "http://order-system"
            type: "com.example.order.confirmed"
            data:
              orderId: ${ .waitForNewOrder.data.orderId }
              status: "CONFIRMED"
              paymentId: ${ .waitForPayment.data.paymentId }
              confirmedAt: ${ new Date().toISOString() }
      then: exit
      
  - handleFailedPayment:
      emit:
        event:
          with:
            source: "http://order-system"
            type: "com.example.order.failed"
            data:
              orderId: ${ .waitForNewOrder.data.orderId }
              status: "PAYMENT_FAILED"
              reason: "Payment processing failed"
              failedAt: ${ new Date().toISOString() }
      then: exit                    
```






### Event-Based Microservice Coordination

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: microservice-coordination
  version: '1.0.0'
use:
  functions:
    createUserProfile:
      call: http
      with:
        method: POST
        endpoint: "http://profile-service/api/v1/profiles"
        headers:
          Content-Type: "application/json"
        body:
          userId: ${ .userId }
          email: ${ .email }
          username: ${ .username }
    sendWelcomeEmail:
      call: http
      with:
        method: POST
        endpoint: "http://email-service/api/v1/emails"
        headers:
          Content-Type: "application/json"
        body:
          to: ${ .to }
          template: "welcome-email"
          data:
            name: ${ .name }
            language: ${ .language }
do:
  - waitForNewUser:
      listen:
        to:
          one:
            with:
              source: "http://user-service"
              type: "com.example.user.registered"
        read: data
      timeout:
        after: PT30M
        
  - processNewUser:
      fork:
        branches:
          - createProfile:
              do:
                - initiateProfileCreation:
                    call: createUserProfile
                    with:
                      args:
                        userId: ${ .waitForNewUser.data.userId }
                        email: ${ .waitForNewUser.data.email }
                        username: ${ .waitForNewUser.data.username }
                
                - notifyProfileCreated:
                    emit:
                      event:
                        with:
                          source: "http://profile-service"
                          type: "com.example.profile.created"
                          data:
                            userId: ${ .waitForNewUser.data.userId }
                            profileId: ${ .initiateProfileCreation.profileId }
                            createdAt: ${ new Date().toISOString() }
                
          - sendWelcome:
              do:
                - initiateWelcomeEmail:
                    call: sendWelcomeEmail
                    with:
                      args:
                        to: ${ .waitForNewUser.data.email }
                        name: ${ .waitForNewUser.data.firstName }
                        language: ${ .waitForNewUser.data.preferences.language || "en" }
                      
                - notifyEmailSent:
                    emit:
                      event:
                        with:
                          source: "http://email-service"
                          type: "com.example.email.sent"
                          data:
                            userId: ${ .waitForNewUser.data.userId }
                            emailId: ${ .initiateWelcomeEmail.emailId }
                            sentAt: ${ new Date().toISOString() }
                
  - completeOnboarding:
      listen:
        to:
          all:
            - with:
                source: "http://profile-service"
                type: "com.example.profile.created"
              correlate:
                userId:
                  from: ${ .data.userId }
                  expect: ${ .waitForNewUser.data.userId }
            - with:
                source: "http://email-service"
                type: "com.example.email.sent"
              correlate:
                userId:
                  from: ${ .data.userId }
                  expect: ${ .waitForNewUser.data.userId }
        read: data
      timeout:
        after: PT30M
        
  - notifyOnboardingCompletion:
      emit:
        event:
          with:
            source: "http://onboarding-service"
            type: "com.example.onboarding.completed"
            data:
              userId: ${ .waitForNewUser.data.userId }
              status: "COMPLETE"
              profileId: ${ .completeOnboarding.data[0].profileId }
              emailSent: true
              completedAt: ${ new Date().toISOString() }
```

Event tasks form the foundation of event-driven architecture within workflows, 
enabling responsive, loosely-coupled systems that can react to changes across distributed environments. 
By using Listen and Emit tasks appropriately, workflows can participate in complex event ecosystems, 
coordinating business processes that span multiple services while maintaining resilience and scalability. 


