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
  dsl: '1.0.3'
  namespace: examples
  name: wait-basic
  version: '1.0.0'
do:
  - startProcess:
      call: startLongRunningJob
      # ...
  - waitForCompletion:
      wait: PT5S # Wait for 5 seconds
  - checkStatus:
      call: getJobStatus
      # Input to checkStatus is the output of startProcess,
      # as Wait just passes data through.
      # ...
```

In this example, after the `startProcess` task completes, the `waitForCompletion` task pauses the workflow for exactly 5
seconds before the `checkStatus` task is executed.

## Configuration Options

Use an ISO 8601 duration such as `wait: PT5S`, an object such as `wait: { seconds: 5 }`, or an expression that produces a duration.

## Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: The `Wait` task typically acts as a pass-through for data; its `rawOutput` is identical to its `transformedInput` unless explicitly changed by `output.as`.

## Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>

## Wait Task Examples

### Basic Delay

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: basic-delay
  version: '1.0.0'
do:
  - sendNotification:
      call: notificationService
      with:
        user: ${ .input.userId }
        message: "Your account verification process has started"
  - waitForProcessing:
      wait: PT30S

  - checkVerificationStatus:
      call: verificationService
      with:
        userId: ${ .input.userId }
      output:
        as: '${ $input + { "verificationStatus": . } }'
```

### Wait Until Specific Time

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: scheduled-execution
  version: '1.0.0'
do:
  - scheduleMaintenanceWindow:
      set:
        maintenanceWindowStart: ${ .input.maintenanceTime // "2023-12-15T02:00:00Z" }

  - notifyUsersAboutMaintenance:
      call: notificationService
      with:
        type: "MAINTENANCE_NOTIFICATION"
        message: ${ "System maintenance scheduled for " + .maintenanceWindowStart }
        users: ${ .input.affectedUsers }

  - waitUntilMaintenanceWindow:
      wait: '${ {seconds: ([0, ((.maintenanceWindowStart | fromdateiso8601) - now | ceil)] | max)} }'
  - performMaintenance:
      call: maintenanceService
      with:
        systems: ${ .input.targetSystems }
        operationType: "SCHEDULED_MAINTENANCE"
```

### Dynamic Wait Duration

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: dynamic-delay
  version: '1.0.0'
do:
  - determineWaitTime:
      set:
        waitTime: '${ if .input.priority == "high" then "PT5S" elif .input.priority == "medium" then "PT1M" else "PT5M" end
          }'
  - waitBasedOnPriority:
      wait: '${ .waitTime }'
  - processItem:
      call: itemProcessor
      with:
        itemId: '${ .input.itemId }'
        processingType: '${ .input.processingType }'
```

### Implementing Polling Pattern

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: polling-pattern
  version: '1.0.0'
do:
  - startLongRunningProcess:
      call: batchProcessor
      with:
        batchId: ${ .input.batchId }
        operationType: "START"
      output:
        as: '${ $input + { "processStatus": . } }'
  - initializePolling:
      set:
        maxAttempts: 10
        currentAttempt: 0
        complete: false

  - pollForCompletion:
      do:
        - iterate:
            do:
              - waitForNextPoll:
                  wait: PT10S

              - incrementAttempt:
                  set:
                    currentAttempt: ${ .currentAttempt + 1 }

              - checkStatus:
                  call: batchProcessor
                  with:
                    batchId: ${ .input.batchId }
                    operationType: "STATUS"
                  output:
                    as: '${ $input + { "processStatus": . } }'
              - updateCompletionStatus:
                  set:
                    complete: ${ .processStatus.status == "COMPLETED" or .processStatus.status == "FAILED" }

        - repeat:
            switch:
              - more:
                  when: ${ (.complete | not) and .currentAttempt < .maxAttempts }
                  then: iterate
              - done:
                  then: exit
  - handleProcessResults:
      do:
        - choose:
            switch:
              - yes:
                  when: ${ .complete }
                  then: onTrue
              - no:
                  then: onFalse
        - onTrue:
            do:
              - returnResults:
                  set:
                    result:
                      batchId: ${ .input.batchId }
                      status: ${ .processStatus.status }
                      details: ${ .processStatus.details }
            then: exit
        - onFalse:
            do:
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
  dsl: '1.0.3'
  namespace: examples
  name: rate-limiting
  version: '1.0.0'
do:
  - processFirstBatch:
      call: dataProcessor
      with:
        batchId: '${ .input.batchIds[0] }'
        data: '${ .input.batches[0] }'
      output:
        as: '${ $input + { "firstBatchResult": . } }'
  - implementCooldown:
      wait: PT30S
      metadata:
        description: "API rate limiting cooldown period"
  - processSecondBatch:
      call: dataProcessor
      with:
        batchId: '${ .input.batchIds[1] }'
        data: '${ .input.batches[1] }'
      output:
        as: '${ $input + { "secondBatchResult": . } }'
  - finalizeResults:
      set:
        result:
          processedBatches: 2
          results: ['${ .firstBatchResult }', '${ .secondBatchResult }']
          timing:
            started: '${ .execution.startTime }'
            completed: '${ (now | todateiso8601) }'
```

### Business Hours Scheduling

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: business-hours-scheduling
  version: '1.0.0'
do:
  - receiveRequest:
      set:
        currentTime: '${ (now | todateiso8601) }'
        targetTime: '${ now as $now | ($now | gmtime) as $utc | ($now - ($now % 86400) + 32400) as $opening | (if $utc[6]
          == 6 then $opening + 172800 elif $utc[6] == 0 then $opening + 86400 elif $utc[3] < 9 then $opening elif $utc[3]
          < 17 then $now elif $utc[6] == 5 then $opening + 259200 else $opening + 86400 end) | todateiso8601 }'
  - scheduleForBusinessHours:
      do:
        - choose:
            switch:
              - yes:
                  when: '${ .targetTime != .currentTime }'
                  then: onTrue
              - no:
                  then: exit
        - onTrue:
            do:
              - waitUntilBusinessHours:
                  wait: '${ {seconds: ([0, ((.targetTime | fromdateiso8601) - now | ceil)] | max)} }'
            then: exit
  - processRequest:
      call: requestProcessor
      with:
        requestId: '${ .input.requestId }'
        data: '${ .input.requestData }'
      output:
        as: '${ $input + { "processingResult": . } }'
```

## Combining Wait with Other Tasks

Time tasks are often combined with other task types to create more complex temporal patterns:

```yaml
document:
  dsl: '1.0.3'
  namespace: examples
  name: exponential-backoff
  version: '1.0.0'
do:
  - submit:
      try:
        - sendRequest:
            call: http
            with:
              method: post
              endpoint: https://api.example.com/jobs
              body: '${ . }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/communication
        retry:
          delay: { seconds: 1 }
          backoff: { exponential: {} }
          limit:
            attempt: { count: 4 }
```