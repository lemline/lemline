---
title: Workflow Definition Examples
---

# Workflow Definition Examples

## Introduction

This page provides a collection of practical examples showing different approaches to defining Serverless Workflows. Each example demonstrates specific use cases, patterns, and combinations of features that can be employed when creating workflow definitions.

These examples range from simple sequential task execution to complex event-driven workflows with parallel processing and error handling. They showcase how the various properties and constructs of the DSL can be combined to solve real-world orchestration challenges.

For a complete reference of all top-level workflow properties, please see [Workflow Definition Structure](dsl-workflow-definition.md).

Feel free to use these examples as starting points or templates for your own workflow definitions. You can modify and extend them to suit your specific requirements.

## Example 1: Basic Sequential Task Execution

This example demonstrates a straightforward workflow with minimal properties and sequential task execution. It represents a simple order processing flow with three consecutive function calls.

**Key features:**
- Proper `document` structure with required workflow properties
- Function definitions in the resource catalog (`use.functions`)
- Sequential task execution with explicit `then` directives
- Function calls with runtime expression arguments

```yaml
document:
  dsl: '1.0.0'
  namespace: com.example.orders
  name: process-new-order
  version: '1.1.0'
  title: Process New Customer Order
  description: Workflow to validate, reserve inventory, and initiate payment for new orders.

# Define reusable function definitions
use:
  functions:
    - name: validateOrderFunc
      operation: https://order-service/validate
      type: rest
    - name: checkInventoryFunc
      operation: https://inventory-service/check
      type: rest
    - name: processPaymentFunc
      operation: https://payment-service/process
      type: rest

# Main execution block with sequential tasks
do:
  - validateOrder:
      call: function
      with:
        function: validateOrderFunc
        arguments:
          order: "${ .inputOrder }"
      then: checkInventory
  - checkInventory:
      call: function
      with:
        function: checkInventoryFunc
        arguments:
          items: "${ .order.items }"
      then: processPayment
  - processPayment:
      call: function
      with:
        function: processPaymentFunc
        arguments:
          amount: "${ .order.total }"
          customerId: "${ .order.customerId }"
```

## Example 2: Event-Driven Workflow with Resource Catalog

This example shows a more sophisticated workflow that leverages the resource catalog for reusable definitions and is triggered by events. It implements a customer communication workflow that sends personalized welcome messages through either email or SMS based on customer preferences.

**Key features:**
- Proper `document` structure with required workflow properties
- Resource catalog (`use`) with function definitions, authentication configurations, and event definitions
- Event-based scheduling with the `schedule.when` property
- Conditional logic using the `switch` task
- Authentication reference in task definitions
- Data extraction from triggering events
- Custom metadata

```yaml
document:
  dsl: '1.0.0'
  namespace: com.example.notifications
  name: customer-communication
  version: '2.0.0'

# Reusable definitions in the resource catalog
use:
  # Function definitions
  functions:
    - name: sendEmailFunc
      operation: https://email-service/send
      type: rest
    - name: sendSmsFunc
      operation: https://sms-service/send
      type: rest
  
  # Authentication definitions  
  auth:
    - name: emailServiceAuth
      scheme: oauth2
      properties:
        grantType: client_credentials
        tokenUrl: https://auth.example.com/token
        clientId: "${ $secrets.EMAIL_SERVICE_CLIENT_ID }"
        clientSecret: "${ $secrets.EMAIL_SERVICE_CLIENT_SECRET }"
  
  # Event definitions
  events:
    - name: CustomerSignupEvent
      source: /customers/signup
      type: com.example.customer.signup.v1

# Event-based scheduling
schedule:
  - when:
      event: CustomerSignupEvent
    start: sendWelcomePackage

# Main execution block
do:
  - sendWelcomePackage:
      set:
        customer: "${ .data }"  # Extract customer data from the triggering event
      then: determineChannel
  - determineChannel:
      switch:
        conditions:
          - condition: "${ .customer.preferences.contactMethod == 'email' }"
            transition: sendEmail
          - condition: "${ .customer.preferences.contactMethod == 'sms' }"
            transition: sendSms
        default: sendEmail
  - sendEmail:
      call: function
      with:
        function: sendEmailFunc
        auth: emailServiceAuth
        arguments:
          to: "${ .customer.email }"
          template: "welcome_email"
          data:
            name: "${ .customer.name }"
            accountType: "${ .customer.accountType }"
  - sendSms:
      call: function
      with:
        function: sendSmsFunc
        arguments:
          to: "${ .customer.phone }"
          message: "Welcome to our service, ${ .customer.name }!"

# Custom metadata
metadata:
  owner: "Customer Engagement Team"
  reviewedDate: "2023-09-15"
  priority: "high"
```

## Example 3: Workflow with Parallel Execution and Error Handling

This example illustrates a data processing workflow with parallel execution, comprehensive error handling, and timeout configuration. It fetches a batch of data, processes multiple records in parallel, aggregates the results, and handles any errors that might occur during processing.

**Key features:**
- Proper `document` structure with required workflow properties
- Workflow-level timeout configuration
- Parallel execution using the `fork` task
- Retry policy with exponential backoff
- Error handling with `try`/`catch`/`finally` blocks
- Event emission for error notification
- Data transformation and aggregation
- Use of system variables like `$error` and `$now()`

```yaml
document:
  dsl: '1.0.0'
  namespace: com.example.dataprocessing
  name: batch-data-processor
  version: '1.0.0'

# Define overall workflow timeout to ensure it doesn't run indefinitely
timeout: 
  minutes: 30

do:
  - fetchData:
      call: http
      with:
        method: GET
        url: https://api.example.com/data/batch/${ .batchId }
      then: processBatch
  - processBatch:
      # Process multiple data items concurrently
      fork:
        items: "${ .records }"
        as: record
        do:
          call: http
          with:
            method: POST
            url: https://processing-service.example.com/process
            body: "${ .record }"
          retry:
            limit:
              attempt.count: 3
            backoff:
              exponential:
                initial: 
                  seconds: 1
                multiplier: 2
                max:
                  seconds: 8
      then: aggregateResults
  - aggregateResults:
      try:
        do:
          call: function
          with:
            function: aggregateResultsFunc
            arguments:
              results: "${ .forkedResults }"
        catch:
          - error:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
            do:
              call: function
              with:
                function: logValidationErrorFunc
                arguments:
                  error: "${ $error }"
            then: notifyFailure
        finally:
          do:
            call: function
            with:
              function: cleanupTempDataFunc
      then: storeFinalResults
  - storeFinalResults:
      call: function
      with:
        function: storeResultsFunc
        arguments:
          data: "${ .aggregatedData }"
          metadata:
            batchId: "${ .batchId }"
            processedAt: "${ $now() }"
  - notifyFailure:
      emit:
        event:
          type: com.example.batch.processing.failed
          source: /data/processing
          data: 
            batchId: "${ .batchId }"
            error: "${ $error }"
```

## Example 4: Time-based Scheduled Workflow

This example shows a simple maintenance workflow that runs on a time-based schedule using cron syntax. It executes a cleanup container and then sends a report of the operation.

**Key features:**
- Proper `document` structure with required workflow properties
- Time-based scheduling with cron syntax
- Container execution with the `run` task
- Command parameters as an array
- Result handling from previous task executions

```yaml
document:
  dsl: '1.0.0'
  namespace: com.example.maintenance
  name: daily-cleanup
  version: '1.0.0'

# Time-based scheduling
schedule:
  cron: '0 0 * * *'  # Run at midnight every day

do:
  - cleanupTempFiles:
      run: container
      with:
        image: maintenance-tools:v1.2
        command: ["/bin/cleanup.sh", "--older-than", "7d", "--dir", "/tmp"]
      then: sendReport
  - sendReport:
      call: function
      with:
        function: sendReportEmailFunc
        arguments:
          report: "${ .result }"
          recipients: ["admin@example.com"]
```

## Example 5: Input/Output Transformation Workflow

This example demonstrates a workflow with input validation, transformation, and output formatting. It processes payment data, ensuring it meets the required schema before processing and formats the output in a standardized way.

**Key features:**
- Proper `document` structure with required workflow properties
- Input schema validation using JSON Schema
- Input transformation with runtime expressions
- Workflow-level output transformation
- Error handling for validation failures

```yaml
document:
  dsl: '1.0.0'
  namespace: com.example.payments
  name: process-payment
  version: '1.0.0'

# Input validation and transformation
input:
  schema:
    type: object
    required: ["paymentId", "amount", "currency"]
    properties:
      paymentId:
        type: string
        pattern: "^PAY-[A-Z0-9]{12}$"
      amount:
        type: number
        minimum: 0.01
      currency:
        type: string
        enum: ["USD", "EUR", "GBP"]
      metadata:
        type: object
  from: "${ {paymentId: .paymentId, amount: .amount, currency: .currency, timestamp: $now()} }"

# Output transformation
output:
  as: "${ {id: .paymentId, status: .status, processedAt: .timestamp, receipt: .receiptUrl} }"

do:
  - validatePayment:
      try:
        do:
          call: function
          with:
            function: validatePaymentFunc
            arguments:
              payment: "${ . }"
        catch:
          - error:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
            do:
              set:
                status: "REJECTED"
                reason: "${ $error.detail }"
              then: end
      then: processPayment
  - processPayment:
      call: function
      with:
        function: processPaymentFunc
        arguments:
          paymentId: "${ .paymentId }"
          amount: "${ .amount }"
          currency: "${ .currency }"
      then: generateReceipt
  - generateReceipt:
      call: function
      with:
        function: generateReceiptFunc
        arguments:
          payment: "${ . }"
      then:
        set:
          status: "COMPLETED"
          receiptUrl: "${ .receiptUrl }"
```