# Wait

## Purpose

The `Wait` task is used to introduce a pause or delay into the workflow execution. It halts the workflow for a specified
duration before proceeding to the next task.

It's primarily used for:

* Implementing timed delays between tasks.
* Waiting for external processes or systems to complete, where a fixed delay is acceptable.
* Rate limiting or throttling workflow execution.
* Scheduling subsequent actions after a specific interval.

## Basic Usage

Here's a simple example of waiting for 5 seconds:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: wait-basic
  version: '1.0.0'
do:
  - startProcess:
      call: startLongRunningJob
      # ...
  - waitForCompletion:
      wait:
        duration: PT5S # Wait for 5 seconds
  - checkStatus:
      call: getJobStatus
      # Input to checkStatus is the output of startProcess,
      # as Wait just passes data through.
      # ...
```

In this example, after the `startProcess` task completes, the `waitForCompletion` task pauses the workflow for exactly 5
seconds before the `checkStatus` task is executed.

## Configuration Options

### `wait` (Object, Required)

This mandatory object defines the duration of the pause.

* **`duration` ** (String, Required):
  A [duration string](https://docs.oracle.com/javase/8/docs/api/java/time/Duration.html) that specifies
  the length of time to wait. It must be in the ISO-8601 duration format (e.g., `PT5S` for 5 seconds, `PT1H30M` for 1
  hour
  and 30 minutes).

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Wait` task typically acts as a pass-through for data; its `rawOutput` is identical to its `transformedInput` unless explicitly changed by `output.as`.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

## Wait Task Examples

### Basic Delay

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: basic-delay
  version: '1.0.0'
do:
  - sendNotification:
      call: function
      with:
        function: notificationService
        args:
          user: ${ .input.userId }
          message: "Your account verification process has started"
  
  - waitForProcessing:
      wait:
        duration: PT30S
  
  - checkVerificationStatus:
      call: function
      with:
        function: verificationService
        args:
          userId: ${ .input.userId }
      result: verificationStatus
```

### Wait Until Specific Time

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: scheduled-execution
  version: '1.0.0'
do:
  - scheduleMaintenanceWindow:
      set:
        maintenanceWindowStart: ${ .input.maintenanceTime || "2023-12-15T02:00:00Z" }
  
  - notifyUsersAboutMaintenance:
      call: function
      with:
        function: notificationService
        args:
          type: "MAINTENANCE_NOTIFICATION"
          message: ${ "System maintenance scheduled for " + .maintenanceWindowStart }
          users: ${ .input.affectedUsers }
  
  - waitUntilMaintenanceWindow:
      wait:
        timestamp: ${ .maintenanceWindowStart }
  
  - performMaintenance:
      call: function
      with:
        function: maintenanceService
        args:
          systems: ${ .input.targetSystems }
          operationType: "SCHEDULED_MAINTENANCE"
```

### Dynamic Wait Duration

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: dynamic-delay
  version: '1.0.0'
do:
  - determineWaitTime:
      set:
        waitTime: ${
          if (.input.priority == "high") {
            "PT5S"  // 5 seconds for high priority
          } else if (.input.priority == "medium") {
            "PT1M"  // 1 minute for medium priority
          } else {
            "PT5M"  // 5 minutes for low priority
          }
        }
  
  - waitBasedOnPriority:
      wait:
        duration: ${ .waitTime }
  
  - processItem:
      call: function
      with:
        function: itemProcessor
        args:
          itemId: ${ .input.itemId }
          processingType: ${ .input.processingType }
```

### Implementing Polling Pattern

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: polling-pattern
  version: '1.0.0'
do:
  - startLongRunningProcess:
      call: function
      with:
        function: batchProcessor
        args:
          batchId: ${ .input.batchId }
          operationType: "START"
      result: processStatus
  
  - initializePolling:
      set:
        maxAttempts: 10
        currentAttempt: 0
        complete: false
  
  - pollForCompletion:
      while: ${ !.complete && .currentAttempt < .maxAttempts }
      do:
        - waitForNextPoll:
            wait:
              duration: PT10S
        
        - incrementAttempt:
            set:
              currentAttempt: ${ .currentAttempt + 1 }
        
        - checkStatus:
            call: function
            with:
              function: batchProcessor
              args:
                batchId: ${ .input.batchId }
                operationType: "STATUS"
            result: processStatus
        
        - updateCompletionStatus:
            set:
              complete: ${ .processStatus.status == "COMPLETED" || .processStatus.status == "FAILED" }
  
  - handleProcessResults:
      if: ${ .complete }
      then:
        - returnResults:
            set:
              result:
                batchId: ${ .input.batchId }
                status: ${ .processStatus.status }
                details: ${ .processStatus.details }
      else:
        - handleTimeout:
            set:
              result:
                batchId: ${ .input.batchId }
                status: "TIMED_OUT"
                message: "Process did not complete within the maximum polling attempts"
```

### Cooldown Period

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: rate-limiting
  version: '1.0.0'
do:
  - processFirstBatch:
      call: function
      with:
        function: dataProcessor
        args:
          batchId: ${ .input.batchIds[0] }
          data: ${ .input.batches[0] }
      result: firstBatchResult
  
  - implementCooldown:
      wait:
        duration: PT30S
        description: "API rate limiting cooldown period"
  
  - processSecondBatch:
      call: function
      with:
        function: dataProcessor
        args:
          batchId: ${ .input.batchIds[1] }
          data: ${ .input.batches[1] }
      result: secondBatchResult
  
  - finalizeResults:
      set:
        result:
          processedBatches: 2
          results: [
            ${ .firstBatchResult },
            ${ .secondBatchResult }
          ]
          timing:
            started: ${ .execution.startTime }
            completed: ${ new Date().toISOString() }
```

### Business Hours Scheduling

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: business-hours-scheduling
  version: '1.0.0'
do:
  - receiveRequest:
      set:
        currentTime: ${ new Date() }
        targetTime: ${
          function getNextBusinessHourTime() {
            const now = new Date();
            const hour = now.getHours();
            const isWeekend = now.getDay() === 0 || now.getDay() === 6;
            
            if (isWeekend) {
              // Move to Monday 9 AM
              const monday = new Date(now);
              monday.setDate(now.getDate() + (8 - now.getDay()) % 7);
              monday.setHours(9, 0, 0, 0);
              return monday.toISOString();
            } else if (hour < 9) {
              // Before business hours, schedule for 9 AM
              const today9am = new Date(now);
              today9am.setHours(9, 0, 0, 0);
              return today9am.toISOString();
            } else if (hour >= 17) {
              // After business hours, schedule for next day 9 AM
              const tomorrow9am = new Date(now);
              tomorrow9am.setDate(now.getDate() + 1);
              tomorrow9am.setHours(9, 0, 0, 0);
              return tomorrow9am.toISOString();
            } else {
              // Within business hours, process now
              return now.toISOString();
            }
          }
          getNextBusinessHourTime()
        }
  
  - scheduleForBusinessHours:
      if: ${ .targetTime != .currentTime }
      then:
        - waitUntilBusinessHours:
            wait:
              timestamp: ${ .targetTime }
              description: "Waiting until business hours to process request"
  
  - processRequest:
      call: function
      with:
        function: requestProcessor
        args:
          requestId: ${ .input.requestId }
          data: ${ .input.requestData }
      result: processingResult
```

## Combining Wait with Other Tasks

Time tasks are often combined with other task types to create more complex temporal patterns:

```yaml
document:
  dsl: '1.0.0'
  namespace: examples
  name: comprehensive-retry-with-backoff
  version: '1.0.0'
do:
  - initializeState:
      set:
        retryCount: 0
        maxRetries: 5
        backoffFactor: 2
        initialDelay: 1
  
  - processWithRetries:
      try:
        do:
          - callExternalService:
              call: function
              with:
                function: externalService
                args:
                  requestData: ${ .input.data }
              result: serviceResponse
        catch:
          as: error
          do:
            - incrementRetryCount:
                set:
                  retryCount: ${ .retryCount + 1 }
                  currentDelay: ${ .initialDelay * Math.pow(.backoffFactor, .retryCount - 1) }
            
            - checkRetryLimit:
                if: ${ .retryCount <= .maxRetries }
                then:
                  - logRetryAttempt:
                      call: function
                      with:
                        function: logger
                        args:
                          level: "INFO"
                          message: ${ "Retrying operation (attempt " + .retryCount + " of " + .maxRetries + ")" }
                          error: ${ .error }
                          nextDelaySeconds: ${ .currentDelay }
                  
                  - implementBackoff:
                      wait:
                        duration: ${ "PT" + .currentDelay + "S" }
                        description: ${ "Exponential backoff delay before retry attempt " + .retryCount }
                  
                  - retry:
                      try:
                        do:
                          - callExternalServiceAgain:
                              call: function
                              with:
                                function: externalService
                                args:
                                  requestData: ${ .input.data }
                                  retryAttempt: ${ .retryCount }
                              result: serviceResponse
                else:
                  - handleMaxRetriesExceeded:
                      set:
                        result:
                          success: false
                          error: "MAX_RETRIES_EXCEEDED"
                          message: "Operation failed after maximum retry attempts"
                          details: {
                            "originalError": ${ .error },
                            "retryAttempts": ${ .retryCount }
                          }
```