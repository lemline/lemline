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
        do:
          - validateOrder:
              call: function
              with:
                function: orderValidator
                args:
                  order: ${ .input.order }
          - processPayment:
              call: function
              with:
                function: paymentProcessor
                args:
                  paymentDetails: ${ .input.paymentDetails }
                  amount: ${ .input.order.totalAmount }
        catch:
          as: error
          do:
            - logError:
                call: function
                with:
                  function: errorLogger
                  args:
                    error: ${ .error }
                    context: "Order processing"
            - notifyCustomer:
                call: function
                with:
                  function: emailSender
                  args:
                    to: ${ .input.order.customerEmail }
                    subject: "Order Processing Failed"
                    body: ${ "We apologize, but we could not process your order. Error: " + .error.message }
```

### Retry Logic for Transient Errors

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: retry-logic
  version: '1.0.0'
do:
  - processExternalAPI:
      try:
        retry:
          max_attempts: 3
          initial_delay: 2
          max_delay: 10
          multiplier: 2
          codes: ["UNAVAILABLE", "RESOURCE_EXHAUSTED"]
        do:
          - callExternalService:
              call: http
              with:
                url: "https://external-api.example.com/process"
                method: "POST"
                body: ${ .input.requestData }
                headers:
                  Content-Type: "application/json"
                  Authorization: ${ "Bearer " + .input.apiKey }
              result: apiResponse
        catch:
          as: error
          when: ${ .error.code == "PERMISSION_DENIED" }
          do:
            - refreshCredentials:
                call: function
                with:
                  function: credentialRefresher
                  args:
                    currentKey: ${ .input.apiKey }
                result: newCredentials
            - retryWithNewCredentials:
                call: http
                with:
                  url: "https://external-api.example.com/process"
                  method: "POST"
                  body: ${ .input.requestData }
                  headers:
                    Content-Type: "application/json"
                    Authorization: ${ "Bearer " + .newCredentials.apiKey }
                result: apiResponse
```

### Conditional Error Handling

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: conditional-error-handling
  version: '1.0.0'
do:
  - processDatabaseOperation:
      try:
        do:
          - updateRecord:
              call: function
              with:
                function: databaseService
                args:
                  operation: "UPDATE"
                  table: "customers"
                  record: ${ .input.customerData }
                  id: ${ .input.customerId }
        catch:
          - as: duplicateError
            when: ${ .error.code == "ALREADY_EXISTS" }
            do:
              - handleDuplicate:
                  set:
                    result:
                      success: false
                      message: "Customer record already exists"
                      errorType: "DUPLICATE_RECORD"
                      suggestion: "Use the existing record or modify your data"
          
          - as: notFoundError
            when: ${ .error.code == "NOT_FOUND" }
            do:
              - createNewRecord:
                  call: function
                  with:
                    function: databaseService
                    args:
                      operation: "CREATE"
                      table: "customers"
                      record: ${ .input.customerData }
          
          - as: unexpectedError
            do:
              - logUnexpectedError:
                  call: function
                  with:
                    function: errorLogger
                    args:
                      severity: "ERROR"
                      message: ${ "Unexpected database error: " + .error.message }
                      stackTrace: ${ .error.stack }
                      metadata: 
                        operation: "UPDATE"
                        table: "customers"
                        customerId: ${ .input.customerId }
              - setFailureResponse:
                  set:
                    result:
                      success: false
                      message: "An unexpected error occurred during database operation"
                      errorCode: ${ .error.code }
                      retryable: ${ .error.code == "UNAVAILABLE" || .error.code == "DEADLINE_EXCEEDED" }
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
            then:
              raise:
                error: "INVALID_ARGUMENT"
                message: "Missing required fields"
                details: ${
                  {
                    "missingFields": [
                      if(!.input.email) "email",
                      if(!.input.username) "username",
                      if(!.input.password) "password"
                    ]
                  }
                }
        
        - validateEmail:
            if: ${ !.input.email.match(/^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/) }
            then:
              raise:
                error: "INVALID_ARGUMENT"
                message: "Invalid email format"
                details: ${
                  {
                    "field": "email",
                    "value": .input.email,
                    "pattern": "username@domain.tld"
                  }
                }
        
        - validatePassword:
            if: ${ .input.password.length < 8 }
            then:
              raise:
                error: "INVALID_ARGUMENT"
                message: "Password too short"
                details: ${
                  {
                    "field": "password",
                    "minLength": 8,
                    "currentLength": .input.password.length
                  }
                }
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
      call: function
      with:
        function: inventoryService
        args:
          productId: ${ .input.productId }
      result: inventoryData
  
  - validateOrder:
      do:
        - checkProductAvailability:
            if: ${ .inventoryData.quantityAvailable < .input.quantity }
            then:
              raise:
                error: "FAILED_PRECONDITION"
                message: "Insufficient inventory"
                details: ${
                  {
                    "productId": .input.productId,
                    "requested": .input.quantity,
                    "available": .inventoryData.quantityAvailable,
                    "restockDate": .inventoryData.nextDeliveryDate
                  }
                }
        
        - checkOrderLimit:
            if: ${ .input.quantity > 10 && .input.customerType != "wholesale" }
            then:
              raise:
                error: "PERMISSION_DENIED"
                message: "Retail customers are limited to 10 units per order"
                details: ${
                  {
                    "customerType": .input.customerType,
                    "maxQuantity": 10,
                    "requestedQuantity": .input.quantity,
                    "resolution": "Apply for a wholesale account or reduce order quantity"
                  }
                }
```

## Combining Try and Raise

A powerful pattern is combining Try and Raise tasks for comprehensive error handling:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: comprehensive-error-handling
  version: '1.0.0'
do:
  - processOrder:
      try:
        do:
          - validateOrder:
              do:
                - checkOrderData:
                    if: ${ !.input.order.items || .input.order.items.length == 0 }
                    then:
                      raise:
                        error: "INVALID_ARGUMENT"
                        message: "Order must contain at least one item"
                
                - verifyPaymentInfo:
                    if: ${ !.input.paymentMethod || !.input.paymentMethod.type }
                    then:
                      raise:
                        error: "INVALID_ARGUMENT"
                        message: "Payment information is required"
          
          - processPayment:
              call: function
              with:
                function: paymentProcessor
                args:
                  order: ${ .input.order }
                  paymentMethod: ${ .input.paymentMethod }
              result: paymentResult
          
          - verifyPaymentSuccess:
              if: ${ !.paymentResult.success }
              then:
                raise:
                  error: "ABORTED"
                  message: ${ "Payment failed: " + .paymentResult.reason }
                  details: ${ .paymentResult }
        
        catch:
          as: orderError
          do:
            - handleOrderError:
                switch:
                  - condition: ${ .orderError.error == "INVALID_ARGUMENT" }
                    next: handleValidationError
                  - condition: ${ .orderError.error == "ABORTED" }
                    next: handlePaymentError
                  - condition: true
                    next: handleGenericError
            
            - handleValidationError:
                set:
                  result:
                    success: false
                    type: "VALIDATION_ERROR"
                    message: ${ .orderError.message }
                    details: ${ .orderError.details || {} }
            
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
            
            - handleGenericError:
                do:
                  - logError:
                      call: function
                      with:
                        function: logger
                        args:
                          level: "ERROR"
                          message: ${ "Unexpected error processing order: " + .input.order.id }
                          error: ${ .orderError }
                  
                  - createSupportTicket:
                      call: function
                      with:
                        function: supportSystem
                        args:
                          issueType: "ORDER_PROCESSING_FAILURE"
                          orderId: ${ .input.order.id }
                          customerEmail: ${ .input.order.customerEmail }
                          errorDetails: ${ .orderError }
                      result: ticketInfo
                  
                  - setErrorResponse:
                      set:
                        result:
                          success: false
                          type: "SYSTEM_ERROR"
                          message: "We encountered an unexpected issue processing your order"
                          supportTicket: ${ .ticketInfo.ticketId }
                          supportEmail: "support@example.com"
```

Error handling tasks are essential for building robust, production-ready workflows. The Try and Raise tasks work together to provide comprehensive error management capabilities, allowing you to create workflows that gracefully handle exceptions, implement recovery strategies, and maintain system reliability even when unexpected conditions occur. 