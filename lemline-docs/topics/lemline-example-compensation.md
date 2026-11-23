# Compensation Pattern Examples

> **Runtime support:** some examples on this page use `call: openapi`, `call: asyncapi`, `call: grpc` or OAuth 2.0 authentication. The Lemline runtime does not execute these yet: such a task fails when it runs. Write those calls with `call: http` and `basic` or `bearer` authentication to run them on Lemline.


This document provides examples of implementing compensation patterns in Lemline workflows. Compensation patterns are essential for maintaining data consistency and handling partial failures in distributed systems by implementing actions that "undo" or "compensate" for previously completed steps when errors occur.

## Basic Compensation Pattern

### Simple Two-Phase Workflow with Compensation

This example demonstrates a simple order processing workflow with reservation and charge steps, with compensation for the reservation if the charge fails.

```yaml
document:
  dsl: 1.0.3
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
            export:
              as: '${ $context + { inventoryReservationId: .inventoryReservation.id } }'
            output:
              as: '${ $input + { "inventoryReservation": . } }'
        - processPayment:
            call: http
            with:
              method: post
              endpoint: https://payments.example/api/charge
              body:
                orderId: ${ .orderId }
                amount: ${ .inventoryReservation.totalAmount }
                paymentInfo: ${ .paymentInfo }
            output:
              as: '${ $input + { "paymentResult": . } }'
      catch:
        as: failure
        when: ${ ($failure.type == "https://example.com/errors/payment/declined") or ($failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/communication") }
        do:
          - selectHandler:
              switch:
                - case1:
                    then: handle1
                    when: ${ $failure.type == "https://example.com/errors/payment/declined" }
                - case2:
                    then: handle2
                    when: ${ $failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/communication" }
          - handle1:
              do:
                - cancelReservation:
                    call: http
                    with:
                      method: post
                      endpoint: https://inventory.example/api/cancel-reservation
                      body:
                        reservationId: ${ $context.inventoryReservationId }
                    output:
                      as: '${ $input + { "cancellationResult": . } }'
                - notifyPaymentFailure:
                    call: http
                    with:
                      method: post
                      endpoint: https://notifications.example/api/send
                      body:
                        orderId: ${ .orderId }
                        template: payment_declined
                        data:
                          reason: ${ $failure.reason }
                - returnErrorResult:
                    set:
                      result:
                        success: false
                        orderId: ${ .orderId }
                        error: Payment declined
                        details: ${ $failure }
                        reservation: cancelled
                    then: exit
              then: exit
          - handle2:
              do:
                - cancelReservation:
                    call: http
                    with:
                      method: post
                      endpoint: https://inventory.example/api/cancel-reservation
                      body:
                        reservationId: ${ $context.inventoryReservationId }
                    output:
                      as: '${ $input + { "cancellationResult": . } }'
                - returnErrorResult:
                    set:
                      result:
                        success: false
                        orderId: ${ .orderId }
                        error: Communication error
                        details: ${ $failure }
                        reservation: cancelled
                    then: exit
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
      output:
        as: '${ $input + { "completedOrder": . } }'
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
  dsl: 1.0.3
  namespace: examples
  name: saga-pattern
  version: 1.0.0
do:
  - initializeSaga:
      set:
        sagaState:
          orderId: ${ $workflow.id }
          customerId: ${ .customerId }
          steps: []
          completedSteps: []
      export:
        as: '${ $context + { sagaState: { orderId: $workflow.id, customerId: .customerId, steps: [], completedSteps: [] }
          } }'

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
            export:
              as: '${ $context + { sagaState: ($context.sagaState + { steps: ($context.sagaState.steps + ["create_order"]),
                completedSteps: ($context.sagaState.completedSteps + ["create_order"]), orderDetails: (.orderCreated)})} }'
            output:
              as: '${ $input + { "orderCreated": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { sagaState: ($context.sagaState + { steps: ($context.sagaState.steps + ["reserve_inventory"]),
                completedSteps: ($context.sagaState.completedSteps + ["reserve_inventory"]), inventoryDetails: (.inventoryReserved)})}
                }'
            output:
              as: '${ $input + { "inventoryReserved": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { sagaState: ($context.sagaState + { steps: ($context.sagaState.steps + ["process_payment"]),
                completedSteps: ($context.sagaState.completedSteps + ["process_payment"]), paymentDetails: (.paymentProcessed)})}
                }'
            output:
              as: '${ $input + { "paymentProcessed": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { sagaState: ($context.sagaState + { steps: ($context.sagaState.steps + ["schedule_shipping"]),
                completedSteps: ($context.sagaState.completedSteps + ["schedule_shipping"]), shippingDetails: (.shippingScheduled)})}
                }'
            output:
              as: '${ $input + { "shippingScheduled": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { sagaState: ($context.sagaState + { steps: ($context.sagaState.steps + ["notify_customer"]),
                completedSteps: ($context.sagaState.completedSteps + ["notify_customer"]), notificationDetails: (.notificationSent)})}
                }'
            output:
              as: '${ $input + { "notificationSent": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
              as: '${ $context + { compensatingActions: [] } }'
        - checkNotifyCustomerStep:
            if: ${ $context.sagaState.completedSteps | index("notify_customer") != null }
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
            export:
              as: '${ $context + { compensatingActions: ($context.compensatingActions + ["notify_customer_cancellation"])}
                }'
            output:
              as: '${ $input + { "cancellationNotificationSent": . } }'
        - checkScheduleShippingStep:
            if: ${ $context.sagaState.completedSteps | index("schedule_shipping") != null }
            call: http
            with:
              method: delete
              endpoint: ${ "https://shipping.example/api/schedules/\($context.sagaState.orderId)" }
            export:
              as: '${ $context + { compensatingActions: ($context.compensatingActions + ["cancel_shipping"])} }'
            output:
              as: '${ $input + { "shippingCancelled": . } }'
        - checkProcessPaymentStep:
            if: ${ $context.sagaState.completedSteps | index("process_payment") != null }
            call: http
            with:
              method: post
              endpoint: https://payments.example/api/refund
              body:
                paymentId: ${ $context.sagaState.paymentDetails.id }
                amount: ${ $context.sagaState.paymentDetails.amount }
                reason: "Order processing failed"
            export:
              as: '${ $context + { compensatingActions: ($context.compensatingActions + ["refund_payment"])} }'
            output:
              as: '${ $input + { "paymentRefunded": . } }'
        - checkReserveInventoryStep:
            if: ${ $context.sagaState.completedSteps | index("reserve_inventory") != null }
            call: http
            with:
              method: delete
              endpoint: ${
                "https://inventory.example/api/reservations/\($context.sagaState.inventoryDetails.reservationId)" }
            export:
              as: '${ $context + { compensatingActions: ($context.compensatingActions + ["release_inventory"])} }'
            output:
              as: '${ $input + { "inventoryReleased": . } }'
        - checkCreateOrderStep:
            if: ${ $context.sagaState.completedSteps | index("create_order") != null }
            call: http
            with:
              method: delete
              endpoint: ${ "https://orders.example/api/orders/\($context.sagaState.orderId)" }
            export:
              as: '${ $context + { compensatingActions: ($context.compensatingActions + ["cancel_order"])} }'
            output:
              as: '${ $input + { "orderCancelled": . } }'
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
  dsl: 1.0.3
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
                  export:
                    as: '${ $context + { inventoryReservationId: .inventoryReservation.id } }'
                  output:
                    as: '${ $input + { "inventoryReservation": . } }'
            catch:
              errors:
                with:
                  type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
                  output:
                    as: '${ $input + { "paymentResult": . } }'
            catch:
              as: failure
              when: ${ ($failure.type == "https://example.com/errors/payment/declined") or ($failure.type ==
                "https://open-workflow-specification.org/spec/1.0.0/errors/communication") }
              do:
                - selectHandler:
                    switch:
                      - case1:
                          then: handle1
                          when: ${ $failure.type == "https://example.com/errors/payment/declined" }
                      - case2:
                          then: handle2
                          when: ${ $failure.type ==
                            "https://open-workflow-specification.org/spec/1.0.0/errors/communication" }
                - handle1:
                    do:
                      - cancelReservation:
                          call: http
                          with:
                            method: post
                            endpoint: https://inventory.example/api/cancel-reservation
                            body:
                              reservationId: ${ $context.inventoryReservationId }
                          output:
                            as: '${ $input + { "cancellationResult": . } }'
                      - notifyPaymentFailure:
                          call: http
                          with:
                            method: post
                            endpoint: https://notifications.example/api/send
                            body:
                              orderId: ${ .orderId }
                              template: payment_declined
                              data:
                                reason: ${ $failure.reason }
                      - returnErrorResult:
                          set:
                            result:
                              success: false
                              orderId: ${ .orderId }
                              error: Payment declined
                              details: ${ $failure }
                              inventoryReservation: cancelled
                          then: exit
                    then: exit
                - handle2:
                    do:
                      - cancelReservation:
                          call: http
                          with:
                            method: post
                            endpoint: https://inventory.example/api/cancel-reservation
                            body:
                              reservationId: ${ $context.inventoryReservationId }
                          output:
                            as: '${ $input + { "cancellationResult": . } }'
                      - returnErrorResult:
                          set:
                            result:
                              success: false
                              orderId: ${ .orderId }
                              error: Payment service communication error
                              details: ${ $failure }
                              inventoryReservation: cancelled
                          then: exit
                    then: exit
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors
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
                    type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            output:
              as: '${ $input + { "completedOrder": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
              output:
                as: '${ $input + { "manualTask": . } }'
  - returnSuccessResult:
      set:
        result:
          success: true
          orderId: ${ .orderId }
          reservationId: ${ $context.inventoryReservationId }
          paymentId: ${ .paymentResult.id }
          completionDetails: ${ .completedOrder // null }
          manualReviewNeeded: ${ .manualTask != null }
```

## Compensation Strategies for Different Resource Types

### Transaction Management with Mixed Resource Types

This example demonstrates different compensation strategies for various resource types (database, message queue, file system, etc.).

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: mixed-resource-compensation
  version: 1.0.0
do:
  - initializeWorkflow:
      set:
        workflowId: ${ $workflow.id }
        allocatedResources: []
      export:
        as: '${ $context + { workflowId: $workflow.id, allocatedResources: [] } }'

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
            export:
              as: '${ $context + { allocatedResources: ($context.allocatedResources + [{ type: ("database"), id: (.dbRecord.id)}])}
                }'
            output:
              as: '${ $input + { "dbRecord": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { allocatedResources: ($context.allocatedResources + [{ type: ("message"), id: (.messageInfo.messageId)}])}
                }'
            output:
              as: '${ $input + { "messageInfo": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { allocatedResources: ($context.allocatedResources + [{ type: ("file"), id: (.fileInfo.fileId),
                path: (.fileInfo.path)}])} }'
            output:
              as: '${ $input + { "fileInfo": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            export:
              as: '${ $context + { allocatedResources: ($context.allocatedResources + [{ type: ("quota"), id: (.quotaInfo.quotaId)}])}
                }'
            output:
              as: '${ $input + { "quotaInfo": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
                        then: compensateBasedOnType-databaseResource
                    - messageResource:
                        when: ${ $resource.type == "message" }
                        then: compensateBasedOnType-messageResource
                    - fileResource:
                        when: ${ $resource.type == "file" }
                        then: compensateBasedOnType-fileResource
                    - quotaResource:
                        when: ${ $resource.type == "quota" }
                        then: compensateBasedOnType-quotaResource
              - compensateBasedOnType-databaseResource:
                  do:
                    - deleteDbRecord:
                        call: http
                        with:
                          method: delete
                          endpoint: ${ "https://database.example/api/records/\($resource.id)" }
                        output:
                          as: '${ $input + { "dbCleanup": . } }'
                  then: compensateBasedOnType-done
              - compensateBasedOnType-messageResource:
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
                        output:
                          as: '${ $input + { "messageCleanup": . } }'
                  then: compensateBasedOnType-done
              - compensateBasedOnType-fileResource:
                  do:
                    - deleteFile:
                        call: http
                        with:
                          method: delete
                          endpoint: ${ "https://storage.example/api/files/\($resource.id)" }
                        output:
                          as: '${ $input + { "fileCleanup": . } }'
                  then: compensateBasedOnType-done
              - compensateBasedOnType-quotaResource:
                  do:
                    - releaseQuota:
                        call: http
                        with:
                          method: delete
                          endpoint: ${ "https://api-gateway.example/api/quotas/\($resource.id)" }
                        output:
                          as: '${ $input + { "quotaCleanup": . } }'
                  then: compensateBasedOnType-done
              - compensateBasedOnType-done:
                  set: ${ . }
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
  dsl: 1.0.3
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
            export:
              as: '${ $context + { jobId: .job.id } }'
            output:
              as: '${ $input + { "job": . } }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
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
            output:
              as: completionEvent
            timeout:
              after:
                hours: 2
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        do:
          - initiateAsyncCleanup:
              call: http
              with:
                method: post
                endpoint: ${ "https://batch-processor.example/api/jobs/\($context.jobId)/cancel" }
              output:
                as: '${ $input + { "cancellationRequest": . } }'
              export:
                as: '$context + { cancellationRequested: true }'
          - waitForCancellationConfirmation:
              try:
                - waitForCancellationConfirmationOperation:
                    listen:
                      to:
                        one:
                          with:
                            type: com.example.job.cancelled
                            correlationId: ${ $workflow.id }
                    output:
                      as: cancellationEvent
                    timeout:
                      after:
                        minutes: 10
              catch:
                errors:
                  with:
                    type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
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
                      output:
                        as: '${ $input + { "manualTask": . } }'
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
10. **Timeouts for Compensation**: Set appropriate timeouts for compensation actions (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)

## Conclusion

These examples demonstrate various compensation patterns for handling partial failures and maintaining data consistency in distributed workflows. By implementing proper compensation strategies, you can build resilient workflows that gracefully handle failures at any stage of execution.

Compensation patterns are essential for maintaining data integrity in distributed systems, preventing resource leaks, and ensuring that business processes remain in a consistent state even when technical failures occur.

For more examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.