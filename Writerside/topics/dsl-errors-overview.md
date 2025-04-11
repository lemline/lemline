---
title: Error Handling Tasks
---

# Error Handling Tasks

Error handling tasks provide mechanisms to manage exceptions, failures, and unexpected conditions in your workflows. They enable you to create resilient workflows that can detect, respond to, and recover from errors gracefully.

## Error Handling Task Types

| Task | Purpose |
|------|---------|
| [Try](dsl-task-try.md) | Execute tasks with error handling and recovery logic |
| [Raise](dsl-task-raise.md) | Explicitly throw errors to signal exceptional conditions |

## When to Use Error Handling Tasks

### Exception Handling with Try

Use the **Try** task when you need to:
- Catch and handle potential errors from risky operations
- Provide alternative execution paths when errors occur
- Implement retry strategies for transient failures
- Clean up resources after errors
- Log and report error details
- Structure error recovery workflows

### Error Signaling with Raise

Use the **Raise** task when you need to:
- Signal that an exceptional condition has occurred
- Abort the current execution path when validation fails
- Create custom error types for specific failure scenarios
- Provide detailed error context to upstream error handlers
- Implement business rule validations that may fail

## Try Task Examples

### Basic Error Handling

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: basic-error-handling
  version: '1.0.0'
do:
  - processOrder:
      try:
        - validateOrder:
            call: http
            with:
              endpoint: "https://api.example.com/validate-order"
              method: "POST"
              body: ${ .input.order }
              headers:
                Content-Type: "application/json"
        - processPayment:
            call: processPayment
            with:
              order: ${ .input.order }
              paymentMethod: ${ .input.paymentMethod }
            result: paymentResult
      catch:
        as: error
        do:
          - logError:
              call: http
              with:
                endpoint: "https://api.example.com/log-error"
                method: "POST"
                body:
                  error: ${ .error }
                  context: "Order processing"
                headers:
                  Content-Type: "application/json"
          - notifyCustomer:
              call: http
              with:
                endpoint: "https://api.example.com/send-email"
                method: "POST"
                body:
                  to: ${ .input.order.customerEmail }
                  subject: "Order Processing Failed"
                  body: "${ 'We apologize, but we could not process your order. Error: ' + .error.message }"
                headers:
                  Content-Type: "application/json"
```

### Retry Logic for Transient Errors

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: retry-logic
  version: '1.0.0'
do:
  - retryTask:
      try:
        - makeHttpCall:
            call: http
            with:
              method: "POST"
              endpoint: "https://external-api.example.com/process"
              headers:
                Content-Type: "application/json"
                Authorization: ${ "Bearer " + $secret.apiKey }
              body: ${ .requestData }
      catch:
        as: error
        errors:
          with:
            type: "PERMISSION_DENIED"
        retry:
          when: ${ .error.code == "UNAVAILABLE" || .error.code == "RESOURCE_EXHAUSTED" }
          delay: PT2S
          backoff:
            exponential:
              multiplier: 2
              maxDelay: PT10S
          limit:
            attempt:
              count: 3
        do:
          - refreshCredentials:
              call: credentialRefresher
              with:
                run:
                  script:
                    language: javascript
                    code: |
                      function credentialRefresher(currentKey) {
                        // Implementation of credential refresh logic
                        return { apiKey: "new-api-key" };
                      }
                    arguments:
                      currentKey: ${ .input.apiKey }
                  return: stdout
          - retryHttpCall:
              call: http
              with:
                method: "POST"
                endpoint: "https://external-api.example.com/process"
                headers:
                  Content-Type: "application/json"
                  Authorization: ${ "Bearer " + .newCredentials.apiKey }
                body: ${ .input.requestData }
```

## Raise Task Examples

### Input Validation

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: input-validation
  version: '1.0.0'
do:
  - validateInput:
      do:
        - checkRequiredFields:
            if: ${ !.input.email || !.input.username || !.input.password }
            raise:
              error:
                type: "https://serverlessworkflow.io/errors/validation/invalid-argument"
                status: 400
                title: "Missing required fields"
                detail: "${ \"The following fields are required but missing: \" + [if(!.input.email) \"email\", if(!.input.username) \"username\", if(!.input.password) \"password\"].filter(Boolean).join(\", \") }"
      
        - validateEmail:
            if: ${ !.input.email.match(/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/) }
            raise:
              error:
                type: "https://serverlessworkflow.io/errors/validation/invalid-argument"
                status: 400
                title: "Invalid email format"
                detail: "${ \"Email address '\" + .input.email + \"' does not match the required format: username@domain.tld\" }"
        
        - validatePassword:
            if: ${ .input.password.length < 8 }
            raise:
              error:
                type: "https://serverlessworkflow.io/errors/validation/invalid-argument"
                status: 400
                title: "Password too short"
                detail: "${ \"Password must be at least 8 characters long. Current length: \" + .input.password.length }"
                
```

### Business Rule Validation

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: business-rule-validation
  version: '1.0.0'
do:
  - checkInventory:
      call: http
      with:
        method: "GET"
        endpoint: "https://api.example.com/inventory"
        query:
          productId: ${ .input.productId }
        headers:
          Content-Type: "application/json"
  
  - validateOrder:
      do:
        - checkProductAvailability:
            if: ${ .inventoryData.quantityAvailable < .input.quantity }
            raise:
              error:
                type: "https://serverlessworkflow.io/errors/validation/failed-precondition"
                status: 412
                title: "Insufficient inventory"
                detail: "${ \"Product '\" + .input.productId + \"' has insufficient inventory. Requested: \" + .input.quantity + \", Available: \" + .inventoryData.quantityAvailable + \". Next restock date: \" + .inventoryData.nextDeliveryDate }"
        
        - checkOrderLimit:
            if: ${ .input.quantity > 10 && .input.customerType != "wholesale" }
            raise:
              error:
                type: "https://serverlessworkflow.io/errors/authorization/permission-denied"
                status: 403
                title: "Retail customers are limited to 10 units per order"
                detail: "${ \"Customer type '\" + .input.customerType + \"' is limited to \" + 10 + \" units per order. Requested quantity: \" + .input.quantity + \". Resolution: Apply for a wholesale account or reduce order quantity.\" }"
```

## Combining Try and Raise

A powerful pattern is combining Try and Raise tasks for comprehensive error handling:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: comprehensive-error-handling
  version: '1.0.0'
use:
  functions:
    - logger:
        ### ... logger implementation
    - supportSystem:
        ### ... support system implementation
do:
  - processOrder:
      try:
        - validateOrder:
            do:
              - checkOrderData:
                  if: ${ !.input.order.items || .input.order.items.length == 0 }
                  raise:
                    error:
                      type: "https://serverlessworkflow.io/errors/validation/invalid-argument"
                      status: 400
                      title: "Order must contain at least one item"
                      detail: "The order must contain at least one item to be processed"
              
              - verifyPaymentInfo:
                  if: ${ !.input.paymentMethod || !.input.paymentMethod.type }
                  raise:
                    error:
                      type: "https://serverlessworkflow.io/errors/validation/invalid-argument"
                      status: 400
                      title: "Payment validation failed"
                      detail: "Payment information is required"
        
        - processPayment:
            call: http
            with:
              method: "POST"
               endpoint:
                uri: "https://api.example.com/payments/process"
                authentication:
                  basic:
                    username: "${ .user }"
                    password: "${ $secrets.pass }"
              headers:
                Content-Type: "application/json"
              body: ${ .input }
        
        - verifyPaymentSuccess:
            if: ${ !.paymentResult.success }
            raise:
              error:
                type: "https://api.example.com/payments/process/errors/ABORTED""
                status: 503
                detail: ${ "Payment failed: " + .paymentResult.reason }
        
      catch:
        as: orderError
        do:
          - handleOrderError:
              switch:
                - condition: ${ .orderError.error == "INVALID_ARGUMENT" }
                  then: handleValidationError
                - condition: ${ .orderError.error == "ABORTED" }
                  then: handlePaymentError
                - condition: true
                  then: handleGenericError
          
          - handleValidationError:
              set:
                result:
                  success: false
                  type: "VALIDATION_ERROR"
                  message: ${ .orderError.message }
                  details: ${ .orderError.details || {} }
              then: exit
          
          - handlePaymentError:
              do:
                - logPaymentIssue:
                    call: function
                    with:
                      function: logger
                      args:
                        level: "WARNING"
                        message: ${ "Payment processing failed for order: " + .input.order.id }
                        data: ${ .orderError }
                
                - suggestAlternativePayment:
                    set:
                      result:
                        success: false
                        type: "PAYMENT_ERROR"
                        message: ${ .orderError.message }
                        suggestedActions: [
                          "Try a different payment method",
                          "Verify your payment details",
                          "Contact your payment provider"
                        ]
              then: exit
          
          - handleGenericError:
              do:
                - logError:
                    call: logger
                    with:
                      args:
                        level: "ERROR"
                        message: ${ "Unexpected error processing order: " + .input.order.id }
                        error: ${ .orderError }
                
                - createSupportTicket:
                    call: supportSystem
                    with:
                      args:
                        issueType: "ORDER_PROCESSING_FAILURE"
                        orderId: ${ .input.order.id }
                        customerEmail: ${ .input.order.customerEmail }
                        errorDetails: ${ .orderError }
                
                - setErrorResponse:
                    set:
                      result:
                        success: false
                        type: "SYSTEM_ERROR"
                        message: "We encountered an unexpected issue processing your order"
                        supportTicket: ${ .ticketInfo.ticketId }
                        supportEmail: "support@example.com"
            then: exit
```

Error handling tasks are essential for building robust, production-ready workflows. The Try and Raise tasks work together to provide comprehensive error management capabilities, allowing you to create workflows that gracefully handle exceptions, implement recovery strategies, and maintain system reliability even when unexpected conditions occur. 