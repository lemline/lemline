---
title: Error Handling
---

# Error Handling Tasks

Error handling tasks provide mechanisms to manage exceptions, failures, and unexpected conditions in your workflows.
They enable you to create resilient workflows that can detect, respond to, and recover from errors gracefully.

## Error Handling Task Types

| Task                       | Purpose                                                  |
|----------------------------|----------------------------------------------------------|
| [Try](dsl-task-try.md)     | Execute tasks with error handling and recovery logic     |
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
  dsl: '1.0.3'
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
            output:
              as: '${ $input + { "paymentResult": . } }'
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
                  body: "${ \"We apologize, but we could not process your order. Error: \" + .error.message }"
                headers:
                  Content-Type: "application/json"
```

### Retry Logic for Transient Errors

```yaml
document:
  dsl: '1.0.3'
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
          when: ${ .error.code == "UNAVAILABLE" or .error.code == "RESOURCE_EXHAUSTED" }
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
  dsl: '1.0.3'
  namespace: examples
  name: input-validation
  version: '1.0.0'
do:
  - validateInput:
      do:
        - checkRequiredFields:
            if: ${ [.input.email, .input.username, .input.password] | any(. == null or . == "") }
            raise:
              error:
                type: "https://open-workflow-specification.org/errors/validation/invalid-argument"
                status: 400
                title: "Missing required fields"
                detail: "${ .input as $in | \"The following fields are required but missing: \" + ([\"email\", \"username\"\
                  , \"password\"] | map(select(($in[.] // \"\") == \"\")) | join(\", \")) }"

        - validateEmail:
            if: ${ (.input.email // "") | test("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}$") | not }
            raise:
              error:
                type: "https://open-workflow-specification.org/errors/validation/invalid-argument"
                status: 400
                title: "Invalid email format"
                detail: "${ \"Email address '\" + .input.email + \"' does not match the required format: username@domain.tld\"\
                  \ }"

        - validatePassword:
            if: ${ .input.password.length < 8 }
            raise:
              error:
                type: "https://open-workflow-specification.org/errors/validation/invalid-argument"
                status: 400
                title: "Password too short"
                detail: "${ \"Password must be at least 8 characters long. Current length: \" + .input.password.length }"

```

### Business Rule Validation

```yaml
document:
  dsl: '1.0.3'
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
                type: "https://open-workflow-specification.org/errors/validation/failed-precondition"
                status: 412
                title: "Insufficient inventory"
                detail: "${ \"Product '\" + .input.productId + \"' has insufficient inventory. Requested: \" + .input.quantity
                  + \", Available: \" + .inventoryData.quantityAvailable + \". Next restock date: \" + .inventoryData.nextDeliveryDate
                  }"

        - checkOrderLimit:
            if: ${ .input.quantity > 10 and .input.customerType != "wholesale" }
            raise:
              error:
                type: "https://open-workflow-specification.org/errors/authorization/permission-denied"
                status: 403
                title: "Retail customers are limited to 10 units per order"
                detail: "${ \"Customer type '\" + .input.customerType + \"' is limited to \" + 10 + \" units per order. Requested
                  quantity: \" + .input.quantity + \". Resolution: Apply for a wholesale account or reduce order quantity.\"\
                  \ }"
```

## Combining Try and Raise

A powerful pattern is combining Try and Raise tasks for comprehensive error handling:

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: comprehensive-error-handling
  version: '1.0.0'
do:
  - processOrder:
      try:
        - validateItems:
            if: '${ (.items // []) | length == 0 }'
            raise:
              error:
                type: https://example.com/errors/invalid-order
                status: 400
                title: Order must contain at least one item
        - processPayment:
            call: http
            with:
              method: post
              endpoint: https://payments.example.com/orders
              body: '${ . }'
      catch:
        as: orderError
        do:
          - reportFailure:
              set:
                success: false
                reason: '${ $orderError.title }'
                status: '${ $orderError.status }'
```

Error handling tasks are essential for building robust, production-ready workflows. The Try and Raise tasks work
together to provide comprehensive error management capabilities, allowing you to create workflows that gracefully handle
exceptions, implement recovery strategies, and maintain system reliability even when unexpected conditions occur. 