# Compensation Pattern Examples

This document provides examples of implementing compensation patterns in Lemline workflows. Compensation patterns are essential for maintaining data consistency and handling partial failures in distributed systems by implementing actions that "undo" or "compensate" for previously completed steps when errors occur.

## Basic Compensation Pattern

### Simple Two-Phase Workflow with Compensation

This example demonstrates a simple order processing workflow with reservation and charge steps, with compensation for the reservation if the charge fails.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: simple-compensation
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - orderId
        - items
        - paymentInfo
do:
  - processOrder:
      try:
        - reserveInventory:
            call: http
            with:
              method: post
              endpoint: https://inventory.example/api/reserve
              body:
                orderId: ${ .orderId }
                items: ${ .items }
              output: inventoryReservation
            export:
              as: '$context + { inventoryReservationId: .inventoryReservation.id }'
        
        - processPayment:
            call: http
            with:
              method: post
              endpoint: https://payments.example/api/charge
              body:
                orderId: ${ .orderId }
                amount: ${ .inventoryReservation.totalAmount }
                paymentInfo: ${ .paymentInfo }
              output: paymentResult
      catch:
        - errors:
            with:
              type: https://example.com/errors/payment/declined
          as: paymentError
          do:
            - cancelReservation:
                call: http
                with:
                  method: post
                  endpoint: https://inventory.example/api/cancel-reservation
                  body:
                    reservationId: ${ $context.inventoryReservationId }
                  output: cancellationResult
            - notifyPaymentFailure:
                call: http
                with:
                  method: post
                  endpoint: https://notifications.example/api/send
                  body:
                    orderId: ${ .orderId }
                    template: "payment_declined"
                    data:
                      reason: ${ $paymentError.reason }
            - returnErrorResult:
                set:
                  result:
                    success: false
                    orderId: ${ .orderId }
                    error: "Payment declined"
                    details: ${ $paymentError }
                    reservation: "cancelled"
                then: exit
        
        - errors:
            with:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
          as: communicationError
          do:
            - cancelReservation:
                call: http
                with:
                  method: post
                  endpoint: https://inventory.example/api/cancel-reservation
                  body:
                    reservationId: ${ $context.inventoryReservationId }
                  output: cancellationResult
            - returnErrorResult:
                set:
                  result:
                    success: false
                    orderId: ${ .orderId }
                    error: "Communication error"
                    details: ${ $communicationError }
                    reservation: "cancelled"
                then: exit

  - completeOrder:
      call: http
      with:
        method: post
        endpoint: https://orders.example/api/complete
        body:
          orderId: ${ .orderId }
          reservationId: ${ $context.inventoryReservationId }
          paymentId: ${ .paymentResult.id }
        output: completedOrder

  - returnSuccessResult:
      set:
        result:
          success: true
          orderId: ${ .orderId }
          reservationId: ${ $context.inventoryReservationId }
          paymentId: ${ .paymentResult.id }
          completionDetails: ${ .completedOrder }
```

## Multi-Step Transaction Compensation

### Saga Pattern Implementation

This example implements the Saga pattern for a multi-step business process with compensation actions for each step.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: saga-pattern
  version: 1.0.0
do:
  - initializeSaga:
      set:
        sagaState:
          orderId: ${ uuidv4() }
          customerId: ${ .customerId }
          steps: []
          completedSteps: []
      export:
        as: '$context + { sagaState: { orderId: uuidv4(), customerId: .customerId, steps: [], completedSteps: [] } }'

  - step1_CreateOrder:
      try:
        - createOrder:
            call: http
            with:
              method: post
              endpoint: https://orders.example/api/orders
              body:
                orderId: ${ $context.sagaState.orderId }
                customerId: ${ $context.sagaState.customerId }
                items: ${ .items }
              output: orderCreated
            export:
              as: '$context + { sagaState: $context.sagaState + { steps: $context.sagaState.steps + ["create_order"], completedSteps: $context.sagaState.completedSteps + ["create_order"], orderDetails: .orderCreated } }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateSaga

  - step2_ReserveInventory:
      try:
        - reserveInventory:
            call: http
            with:
              method: post
              endpoint: https://inventory.example/api/reserve
              body:
                orderId: ${ $context.sagaState.orderId }
                items: ${ .items }
              output: inventoryReserved
            export:
              as: '$context + { sagaState: $context.sagaState + { steps: $context.sagaState.steps + ["reserve_inventory"], completedSteps: $context.sagaState.completedSteps + ["reserve_inventory"], inventoryDetails: .inventoryReserved } }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateSaga

  - step3_ProcessPayment:
      try:
        - processPayment:
            call: http
            with:
              method: post
              endpoint: https://payments.example/api/process
              body:
                orderId: ${ $context.sagaState.orderId }
                customerId: ${ $context.sagaState.customerId }
                amount: ${ $context.sagaState.inventoryDetails.totalAmount }
                paymentMethod: ${ .paymentMethod }
              output: paymentProcessed
            export:
              as: '$context + { sagaState: $context.sagaState + { steps: $context.sagaState.steps + ["process_payment"], completedSteps: $context.sagaState.completedSteps + ["process_payment"], paymentDetails: .paymentProcessed } }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateSaga

  - step4_ScheduleShipping:
      try:
        - scheduleShipping:
            call: http
            with:
              method: post
              endpoint: https://shipping.example/api/schedule
              body:
                orderId: ${ $context.sagaState.orderId }
                items: ${ .items }
                shippingAddress: ${ .shippingAddress }
              output: shippingScheduled
            export:
              as: '$context + { sagaState: $context.sagaState + { steps: $context.sagaState.steps + ["schedule_shipping"], completedSteps: $context.sagaState.completedSteps + ["schedule_shipping"], shippingDetails: .shippingScheduled } }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateSaga

  - step5_NotifyCustomer:
      try:
        - sendNotification:
            call: http
            with:
              method: post
              endpoint: https://notifications.example/api/send
              body:
                orderId: ${ $context.sagaState.orderId }
                customerId: ${ $context.sagaState.customerId }
                template: "order_confirmation"
                data:
                  orderDetails: ${ $context.sagaState.orderDetails }
                  shippingDetails: ${ $context.sagaState.shippingDetails }
              output: notificationSent
            export:
              as: '$context + { sagaState: $context.sagaState + { steps: $context.sagaState.steps + ["notify_customer"], completedSteps: $context.sagaState.completedSteps + ["notify_customer"], notificationDetails: .notificationSent } }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        as: notificationError
        do:
          - logNotificationFailure:
              call: http
              with:
                method: post
                endpoint: https://logging.example/api/log
                body:
                  level: "warning"
                  message: "Failed to send notification, but order process is complete"
                  orderId: ${ $context.sagaState.orderId }
                  error: ${ $notificationError }
          # Note: We don't compensate for notification failure as it's not critical

  - sagaCompleted:
      set:
        result:
          success: true
          orderId: ${ $context.sagaState.orderId }
          steps: ${ $context.sagaState.completedSteps }
          orderDetails: ${ $context.sagaState.orderDetails }
          paymentDetails: ${ $context.sagaState.paymentDetails }
          shippingDetails: ${ $context.sagaState.shippingDetails }

  - compensateSaga:
      do:
        - determineCompensatingActions:
            set:
              compensatingActions: []
            export:
              as: '$context + { compensatingActions: [] }'
        
        - checkNotifyCustomerStep:
            if: ${ "notify_customer" in $context.sagaState.completedSteps }
            call: http
            with:
              method: post
              endpoint: https://notifications.example/api/send
              body:
                orderId: ${ $context.sagaState.orderId }
                customerId: ${ $context.sagaState.customerId }
                template: "order_cancelled"
                data:
                  reason: "Technical error"
              output: cancellationNotificationSent
            export:
              as: '$context + { compensatingActions: $context.compensatingActions + ["notify_customer_cancellation"] }'
        
        - checkScheduleShippingStep:
            if: ${ "schedule_shipping" in $context.sagaState.completedSteps }
            call: http
            with:
              method: delete
              endpoint: https://shipping.example/api/schedules/${$context.sagaState.orderId}
              output: shippingCancelled
            export:
              as: '$context + { compensatingActions: $context.compensatingActions + ["cancel_shipping"] }'
        
        - checkProcessPaymentStep:
            if: ${ "process_payment" in $context.sagaState.completedSteps }
            call: http
            with:
              method: post
              endpoint: https://payments.example/api/refund
              body:
                paymentId: ${ $context.sagaState.paymentDetails.id }
                amount: ${ $context.sagaState.paymentDetails.amount }
                reason: "Order processing failed"
              output: paymentRefunded
            export:
              as: '$context + { compensatingActions: $context.compensatingActions + ["refund_payment"] }'
        
        - checkReserveInventoryStep:
            if: ${ "reserve_inventory" in $context.sagaState.completedSteps }
            call: http
            with:
              method: delete
              endpoint: https://inventory.example/api/reservations/${$context.sagaState.inventoryDetails.reservationId}
              output: inventoryReleased
            export:
              as: '$context + { compensatingActions: $context.compensatingActions + ["release_inventory"] }'
        
        - checkCreateOrderStep:
            if: ${ "create_order" in $context.sagaState.completedSteps }
            call: http
            with:
              method: delete
              endpoint: https://orders.example/api/orders/${$context.sagaState.orderId}
              output: orderCancelled
            export:
              as: '$context + { compensatingActions: $context.compensatingActions + ["cancel_order"] }'
        
        - returnCompensationResult:
            set:
              result:
                success: false
                orderId: ${ $context.sagaState.orderId }
                error: "Saga compensated due to failure"
                completedSteps: ${ $context.sagaState.completedSteps }
                compensatingActions: ${ $context.compensatingActions }
```

## Compensation with Retry

### Resilient Order Processing with Retries and Compensation

This example demonstrates combining retry policies with compensation actions for a more resilient workflow.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: compensation-with-retry
  version: 1.0.0
input:
  schema:
    format: json
    document:
      type: object
      required:
        - orderId
        - items
        - paymentInfo
use:
  retries:
    transientFailure:
      delay:
        seconds: 2
      backoff:
        exponential: {}
      limit:
        attempt:
          count: 3
do:
  - processOrder:
      try:
        - reserveInventory:
            try:
              - callInventoryService:
                  call: http
                  with:
                    method: post
                    endpoint: https://inventory.example/api/reserve
                    body:
                      orderId: ${ .orderId }
                      items: ${ .items }
                    output: inventoryReservation
                  export:
                    as: '$context + { inventoryReservationId: .inventoryReservation.id }'
            catch:
              errors:
                with:
                  type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
              retry: transientFailure
              as: reservationError
              do:
                - logReservationFailure:
                    call: http
                    with:
                      method: post
                      endpoint: https://logging.example/api/log
                      body:
                        level: "error"
                        message: "Failed to reserve inventory after retries"
                        orderId: ${ .orderId }
                        error: ${ $reservationError }
                - setErrorResult:
                    set:
                      result:
                        success: false
                        orderId: ${ .orderId }
                        error: "Inventory reservation failed"
                        details: ${ $reservationError }
                    then: exit
        
        - processPayment:
            try:
              - callPaymentService:
                  call: http
                  with:
                    method: post
                    endpoint: https://payments.example/api/charge
                    body:
                      orderId: ${ .orderId }
                      amount: ${ .inventoryReservation.totalAmount }
                      paymentInfo: ${ .paymentInfo }
                    output: paymentResult
            catch:
              - errors:
                  with:
                    type: https://example.com/errors/payment/declined
                as: paymentError
                do:
                  - cancelReservation:
                      call: http
                      with:
                        method: post
                        endpoint: https://inventory.example/api/cancel-reservation
                        body:
                          reservationId: ${ $context.inventoryReservationId }
                        output: cancellationResult
                  - notifyPaymentFailure:
                      call: http
                      with:
                        method: post
                        endpoint: https://notifications.example/api/send
                        body:
                          orderId: ${ .orderId }
                          template: "payment_declined"
                          data:
                            reason: ${ $paymentError.reason }
                  - returnErrorResult:
                      set:
                        result:
                          success: false
                          orderId: ${ .orderId }
                          error: "Payment declined"
                          details: ${ $paymentError }
                          inventoryReservation: "cancelled"
                      then: exit
              
              - errors:
                  with:
                    type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                retry: transientFailure
                as: communicationError
                do:
                  - cancelReservation:
                      call: http
                      with:
                        method: post
                        endpoint: https://inventory.example/api/cancel-reservation
                        body:
                          reservationId: ${ $context.inventoryReservationId }
                        output: cancellationResult
                  - returnErrorResult:
                      set:
                        result:
                          success: false
                          orderId: ${ .orderId }
                          error: "Payment service communication error"
                          details: ${ $communicationError }
                          inventoryReservation: "cancelled"
                      then: exit
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors
        as: unexpectedError
        do:
          - performEmergencyCleanup:
              try:
                - releaseInventoryIfReserved:
                    if: ${ $context.inventoryReservationId != null }
                    call: http
                    with:
                      method: post
                      endpoint: https://inventory.example/api/cancel-reservation
                      body:
                        reservationId: ${ $context.inventoryReservationId }
                        force: true
              catch:
                errors:
                  with:
                    type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                do:
                  - logCleanupFailure:
                      call: http
                      with:
                        method: post
                        endpoint: https://logging.example/api/log
                        body:
                          level: "critical"
                          message: "Failed to cleanup resources during compensation"
                          orderId: ${ .orderId }
          - logUnexpectedError:
              call: http
              with:
                method: post
                endpoint: https://logging.example/api/log
                body:
                  level: "critical"
                  message: "Unexpected error during order processing"
                  orderId: ${ .orderId }
                  error: ${ $unexpectedError }
          - returnCriticalErrorResult:
              set:
                result:
                  success: false
                  orderId: ${ .orderId }
                  error: "Critical error"
                  details: ${ $unexpectedError }
                  cleanup: "attempted"
              then: exit

  - completeOrder:
      try:
        - finalizeOrder:
            call: http
            with:
              method: post
              endpoint: https://orders.example/api/complete
              body:
                orderId: ${ .orderId }
                reservationId: ${ $context.inventoryReservationId }
                paymentId: ${ .paymentResult.id }
              output: completedOrder
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        retry: transientFailure
        as: finalizationError
        do:
          - logFinalizationWarning:
              call: http
              with:
                method: post
                endpoint: https://logging.example/api/log
                body:
                  level: "warning"
                  message: "Order completion API call failed, but payment and inventory are processed"
                  orderId: ${ .orderId }
                  error: ${ $finalizationError }
          - triggerManualOrderReview:
              call: http
              with:
                method: post
                endpoint: https://workflow.example/api/manual-tasks
                body:
                  type: "order_finalization_needed"
                  orderId: ${ .orderId }
                  reservationId: ${ $context.inventoryReservationId }
                  paymentId: ${ .paymentResult.id }
                  error: ${ $finalizationError }
                output: manualTask

  - returnSuccessResult:
      set:
        result:
          success: true
          orderId: ${ .orderId }
          reservationId: ${ $context.inventoryReservationId }
          paymentId: ${ .paymentResult.id }
          completionDetails: ${ .completedOrder || null }
          manualReviewNeeded: ${ .manualTask != null }
```

## Compensation Strategies for Different Resource Types

### Transaction Management with Mixed Resource Types

This example demonstrates different compensation strategies for various resource types (database, message queue, file system, etc.).

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: mixed-resource-compensation
  version: 1.0.0
do:
  - initializeWorkflow:
      set:
        workflowId: ${ uuidv4() }
        allocatedResources: []
      export:
        as: '$context + { workflowId: uuidv4(), allocatedResources: [] }'

  - step1_CreateDatabaseRecord:
      try:
        - insertRecord:
            call: http
            with:
              method: post
              endpoint: https://database.example/api/records
              body:
                type: "transaction"
                workflowId: ${ $context.workflowId }
                data: ${ .transactionData }
              output: dbRecord
            export:
              as: '$context + { allocatedResources: $context.allocatedResources + [{ type: "database", id: .dbRecord.id }] }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateResources

  - step2_SendToMessageQueue:
      try:
        - publishMessage:
            call: asyncapi
            with:
              document:
                endpoint: file:///apis/messaging.asyncapi.json
              operation: publishEvent
              message:
                payload:
                  workflowId: ${ $context.workflowId }
                  recordId: ${ .dbRecord.id }
                  action: "process"
              output: messageInfo
            export:
              as: '$context + { allocatedResources: $context.allocatedResources + [{ type: "message", id: .messageInfo.messageId }] }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateResources

  - step3_CreateFileResource:
      try:
        - generateFile:
            call: http
            with:
              method: post
              endpoint: https://storage.example/api/files
              body:
                workflowId: ${ $context.workflowId }
                content: ${ .fileContent }
                format: "pdf"
              output: fileInfo
            export:
              as: '$context + { allocatedResources: $context.allocatedResources + [{ type: "file", id: .fileInfo.fileId, path: .fileInfo.path }] }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateResources

  - step4_AllocateApiResource:
      try:
        - allocateApiQuota:
            call: http
            with:
              method: post
              endpoint: https://api-gateway.example/api/quotas
              body:
                workflowId: ${ $context.workflowId }
                service: "premium"
                amount: ${ .quotaAmount }
              output: quotaInfo
            export:
              as: '$context + { allocatedResources: $context.allocatedResources + [{ type: "quota", id: .quotaInfo.quotaId }] }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        then: compensateResources

  - finalizeWorkflow:
      set:
        result:
          success: true
          workflowId: ${ $context.workflowId }
          dbRecordId: ${ .dbRecord.id }
          messageId: ${ .messageInfo.messageId }
          fileId: ${ .fileInfo.fileId }
          quotaId: ${ .quotaInfo.quotaId }

  - compensateResources:
      do:
        - reverseResources:
            for:
              in: ${ $context.allocatedResources }
              each: resource
            do:
              - compensateBasedOnType:
                  switch:
                    - databaseResource:
                        when: ${ $resource.type == "database" }
                        then:
                          do:
                            - deleteDbRecord:
                                call: http
                                with:
                                  method: delete
                                  endpoint: https://database.example/api/records/${$resource.id}
                                  output: dbCleanup
                    
                    - messageResource:
                        when: ${ $resource.type == "message" }
                        then:
                          do:
                            - cancelMessage:
                                call: asyncapi
                                with:
                                  document:
                                    endpoint: file:///apis/messaging.asyncapi.json
                                  operation: cancelMessage
                                  message:
                                    payload:
                                      messageId: ${ $resource.id }
                                      workflowId: ${ $context.workflowId }
                                  output: messageCleanup
                    
                    - fileResource:
                        when: ${ $resource.type == "file" }
                        then:
                          do:
                            - deleteFile:
                                call: http
                                with:
                                  method: delete
                                  endpoint: https://storage.example/api/files/${$resource.id}
                                  output: fileCleanup
                    
                    - quotaResource:
                        when: ${ $resource.type == "quota" }
                        then:
                          do:
                            - releaseQuota:
                                call: http
                                with:
                                  method: delete
                                  endpoint: https://api-gateway.example/api/quotas/${$resource.id}
                                  output: quotaCleanup
        
        - logCompensation:
            call: http
            with:
              method: post
              endpoint: https://logging.example/api/log
              body:
                level: "warning"
                message: "Workflow compensated due to error"
                workflowId: ${ $context.workflowId }
                compensatedResources: ${ $context.allocatedResources }
        
        - returnErrorResult:
            set:
              result:
                success: false
                workflowId: ${ $context.workflowId }
                error: "Workflow failed and resources were compensated"
                compensatedResources: ${ $context.allocatedResources }
```

## Asynchronous Compensation

### Long-Running Process with Asynchronous Compensation

This example demonstrates handling compensation for long-running processes that may require asynchronous cleanup.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: async-compensation
  version: 1.0.0
do:
  - initiateLongRunningProcess:
      try:
        - startProcess:
            call: http
            with:
              method: post
              endpoint: https://batch-processor.example/api/jobs
              body:
                jobType: "data_processing"
                inputData: ${ .inputData }
                callbackEvent:
                  type: "com.example.job.completed"
                  correlationId: ${ $workflow.id }
              output: job
            export:
              as: '$context + { jobId: .job.id }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
        as: startError
        do:
          - handleStartupFailure:
              set:
                result:
                  success: false
                  error: "Failed to initiate job"
                  details: ${ $startError }
              then: exit

  - waitForJobCompletion:
      try:
        - waitForCompletionEvent:
            listen:
              to:
                one:
                  with:
                    type: com.example.job.completed
                    correlationId: ${ $workflow.id }
              for:
                hours: 2
            output:
              as: completionEvent
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        do:
          - initiateAsyncCleanup:
              call: http
              with:
                method: post
                endpoint: https://batch-processor.example/api/jobs/${$context.jobId}/cancel
                output: cancellationRequest
            export:
              as: '$context + { cancellationRequested: true }'
          - waitForCancellationConfirmation:
              listen:
                to:
                  one:
                    with:
                      type: com.example.job.cancelled
                      correlationId: ${ $workflow.id }
                for:
                  minutes: 10
              output:
                as: cancellationEvent
              catch:
                errors:
                  with:
                    type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
                do:
                  - escalateCancellation:
                      call: http
                      with:
                        method: post
                        endpoint: https://workflow.example/api/manual-tasks
                        body:
                          type: "force_job_cancellation"
                          jobId: ${ $context.jobId }
                          workflowId: ${ $workflow.id }
                          reason: "Cancellation confirmation timed out"
                        output: manualTask
                  - setEscalationResult:
                      set:
                        result:
                          success: false
                          error: "Job timeout, cancellation escalated to operations team"
                          jobId: ${ $context.jobId }
                          taskId: ${ .manualTask.id }
                      then: exit

  - processResults:
      if: ${ $context.cancellationRequested != true }
      set:
        result:
          success: true
          jobId: ${ $context.jobId }
          processingResults: ${ .completionEvent.data.results }

  - handleCancellation:
      if: ${ $context.cancellationRequested == true }
      set:
        result:
          success: false
          error: "Job cancelled due to timeout"
          jobId: ${ $context.jobId }
          cancellationDetails: ${ .cancellationEvent }
```

## Best Practices for Compensation Patterns

When implementing compensation patterns in Lemline workflows, consider these best practices:

1. **Resource Tracking**: Keep track of all resources allocated during workflow execution
2. **Compensate in Reverse Order**: Apply compensating actions in reverse order of the original operations
3. **Idempotent Compensation**: Design compensation actions to be safely retryable
4. **Partial Compensation**: Handle scenarios where only some steps need compensation
5. **Async Compensation**: For long-running processes, implement asynchronous compensation
6. **Compensation Logging**: Log all compensation actions for auditing and troubleshooting
7. **Escalation Policy**: Define clear escalation paths when compensation fails
8. **Independent Compensation**: Design compensation actions that don't depend on workflow state
9. **Explicit Cleanup**: Always explicitly clean up resources, don't rely on implicit cleanup
10. **Timeouts for Compensation**: Set appropriate timeouts for compensation actions

## Conclusion

These examples demonstrate various compensation patterns for handling partial failures and maintaining data consistency in distributed workflows. By implementing proper compensation strategies, you can build resilient workflows that gracefully handle failures at any stage of execution.

Compensation patterns are essential for maintaining data integrity in distributed systems, preventing resource leaks, and ensuring that business processes remain in a consistent state even when technical failures occur.

For more examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.