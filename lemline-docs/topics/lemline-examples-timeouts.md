# Timeout Handling Examples

This document provides examples of implementing effective timeout strategies in Lemline workflows. Proper timeout handling is essential for building robust workflows that gracefully handle slow or unresponsive services, preventing workflows from hanging indefinitely and ensuring predictable execution times.

## Basic Timeout Handling

### HTTP Request with Timeout

This example demonstrates a simple HTTP request with a timeout and appropriate error handling.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: basic-timeout
  version: 1.0.0
do:
  - callExternalService:
      try:
        - makeApiCall:
            call: http
            with:
              method: get
              endpoint: https://api.example/data
              timeout:
                seconds: 5  # Request will timeout after 5 seconds
              output: apiResponse
      catch:
        - errors:
            with:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
          as: timeoutError
          do:
            - handleTimeout:
                set:
                  result:
                    success: false
                    reason: "API call timed out"
                    details: "Service did not respond within 5 seconds"
        - errors:
            with:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
          as: communicationError
          do:
            - handleCommunicationError:
                set:
                  result:
                    success: false
                    reason: "Communication error"
                    details: ${ $communicationError }
```

### Graduated Timeouts

This example shows how to implement graduated timeouts for different types of requests.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: graduated-timeouts
  version: 1.0.0
do:
  - fastQuickLookup:
      try:
        - callCacheService:
            call: http
            with:
              method: get
              endpoint: https://cache.example/lookup/${.itemId}
              timeout:
                milliseconds: 500  # Very short timeout for cache lookup
              output: cacheResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        as: timeoutError
        do:
          - setDefaultCache:
              set:
                cacheResult: null
                cacheHit: false
              export:
                as: '$context + { cacheHit: false }'

  - conditionalDatabaseCall:
      if: ${ $context.cacheHit != true }
      try:
        - callDatabaseService:
            call: http
            with:
              method: get
              endpoint: https://db.example/items/${.itemId}
              timeout:
                seconds: 3  # Longer timeout for database lookup
              output: dbResult
            export:
              as: '$context + { dbResult: .dbResult, dbLookupSuccess: true }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        as: dbTimeoutError
        export:
          as: '$context + { dbLookupSuccess: false, dbTimeoutError: $dbTimeoutError }'

  - conditionalSlowProcessing:
      if: ${ $context.dbLookupSuccess != true }
      try:
        - callFallbackService:
            call: http
            with:
              method: get
              endpoint: https://fallback.example/compute/${.itemId}
              timeout:
                seconds: 10  # Longest timeout for complex computation
              output: fallbackResult
            export:
              as: '$context + { fallbackResult: .fallbackResult, fallbackSuccess: true }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        as: fallbackTimeoutError
        export:
          as: '$context + { fallbackSuccess: false, fallbackTimeoutError: $fallbackTimeoutError }'

  - prepareResponse:
      switch:
        - cachedResult:
            when: ${ $context.cacheHit == true }
            then:
              set:
                result:
                  source: "cache"
                  data: ${ $context.cacheResult }
        - dbResult:
            when: ${ $context.dbLookupSuccess == true }
            then:
              set:
                result:
                  source: "database"
                  data: ${ $context.dbResult }
        - fallbackResult:
            when: ${ $context.fallbackSuccess == true }
            then:
              set:
                result:
                  source: "fallback"
                  data: ${ $context.fallbackResult }
        - default:
            then:
              set:
                result:
                  source: "default"
                  data: null
                  error: "All data sources timed out"
```

## Timeouts with Event Waiting

### Event Timeout

This example demonstrates how to implement a timeout when waiting for an external event.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: event-timeout
  version: 1.0.0
do:
  - initiateProcess:
      call: http
      with:
        method: post
        endpoint: https://process.example/start
        body:
          processId: ${ uuidv4() }
          data: ${ .inputData }
        output: process
      export:
        as: '$context + { processId: .process.id }'

  - waitForCompletion:
      listen:
        to:
          one:
            with:
              type: com.example.process.completed
              correlationId: ${ $context.processId }
        for:
          minutes: 5  # If event doesn't arrive within 5 minutes, timeout occurs
      output:
        as: completionEvent
      catch:
        - errors:
            with:
              type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
          do:
            - handleEventTimeout:
                do:
                  - checkProcessStatus:
                      call: http
                      with:
                        method: get
                        endpoint: https://process.example/status/${$context.processId}
                        output: processStatus
                  - decideFurtherAction:
                      switch:
                        - processStillRunning:
                            when: ${ .processStatus.status == "running" }
                            then: extendTimeout
                        - processCompleted:
                            when: ${ .processStatus.status == "completed" }
                            then: simulateEvent
                        - default:
                            then: cancelProcess

  - extendTimeout:
      do:
        - logExtension:
            call: http
            with:
              method: post
              endpoint: https://logging.example/log
              body:
                level: "warning"
                message: "Extending process timeout"
                processId: ${ $context.processId }
        - retryEventWait:
            listen:
              to:
                one:
                  with:
                    type: com.example.process.completed
                    correlationId: ${ $context.processId }
              for:
                minutes: 10  # Extended timeout
            output:
              as: completionEvent
            catch:
              errors:
                with:
                  type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
              do:
                - forceCancel:
                    then: cancelProcess

  - simulateEvent:
      set:
        completionEvent:
          type: "com.example.process.completed"
          processId: ${ $context.processId }
          data: ${ .processStatus.result }
          simulatedEvent: true
      then: processResult

  - cancelProcess:
      do:
        - sendCancellation:
            call: http
            with:
              method: post
              endpoint: https://process.example/cancel/${$context.processId}
              output: cancellationResult
        - logCancellation:
            call: http
            with:
              method: post
              endpoint: https://logging.example/log
              body:
                level: "error"
                message: "Process cancelled due to timeout"
                processId: ${ $context.processId }
        - setErrorResult:
            set:
              result:
                success: false
                reason: "Process timed out and was cancelled"
                processId: ${ $context.processId }
      then: exit

  - processResult:
      set:
        result:
          success: true
          processId: ${ $context.processId }
          data: ${ .completionEvent.data }
          simulatedEvent: ${ .completionEvent.simulatedEvent == true }
```

### Multiple Event Timeout

This example demonstrates waiting for multiple events with a timeout.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: multi-event-timeout
  version: 1.0.0
do:
  - initiateParallelProcesses:
      do:
        - startProcessA:
            call: http
            with:
              method: post
              endpoint: https://process.example/a/start
              body:
                processId: ${ "a-" + uuidv4() }
              output: processA
            export:
              as: '$context + { processAId: .processA.id }'
        - startProcessB:
            call: http
            with:
              method: post
              endpoint: https://process.example/b/start
              body:
                processId: ${ "b-" + uuidv4() }
              output: processB
            export:
              as: '$context + { processBId: .processB.id }'

  - waitForProcesses:
      listen:
        to:
          all:
            - with:
                type: com.example.process.a.completed
                correlationId: ${ $context.processAId }
              as: processAEvent
            - with:
                type: com.example.process.b.completed
                correlationId: ${ $context.processBId }
              as: processBEvent
        for:
          minutes: 10  # Timeout if both events don't arrive within 10 minutes
      output:
        as: completionEvents
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        do:
          - handlePartialCompletion:
              call: http
              with:
                method: post
                endpoint: https://process.example/check-status
                body:
                  processes: [
                    { id: ${ $context.processAId }, type: "a" },
                    { id: ${ $context.processBId }, type: "b" }
                  ]
                output: processStatuses
              export:
                as: '$context + { processStatuses: .processStatuses }'
          - cleanupUnfinishedProcesses:
              for:
                in: "${ $context.processStatuses.unfinished }"
                each: process
              do:
                - cancelProcess:
                    call: http
                    with:
                      method: post
                      endpoint: https://process.example/${$process.type}/cancel/${$process.id}
          - setPartialResult:
              set:
                result:
                  success: false
                  reason: "Partial completion - timeout occurred"
                  completed: ${ $context.processStatuses.finished }
                  cancelled: ${ $context.processStatuses.unfinished }
              then: exit

  - combineResults:
      set:
        result:
          success: true
          processAResult: ${ .completionEvents.processAEvent.data }
          processBResult: ${ .completionEvents.processBEvent.data }
```

## Workflow-Level Timeouts

### Global Workflow Timeout

This example demonstrates implementing a global timeout for an entire workflow.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: global-workflow-timeout
  version: 1.0.0
timeouts:
  workflow:
    duration:
      minutes: 30  # Entire workflow must complete within 30 minutes
do:
  - setupMonitor:
      set:
        workflowStartTime: ${ now() }
        timeoutAt: ${ dateTimeAdd(now(), { minutes: 30 }) }
      export:
        as: '$context + { workflowStartTime: now(), timeoutAt: dateTimeAdd(now(), { minutes: 30 }) }'

  - longRunningProcess:
      do:
        - step1:
            call: http
            with:
              method: post
              endpoint: https://process.example/step1
              timeout:
                minutes: 5
              output: step1Result
        - step2:
            call: http
            with:
              method: post
              endpoint: https://process.example/step2
              body:
                step1Id: ${ .step1Result.id }
              timeout:
                minutes: 10
              output: step2Result
        - step3:
            call: http
            with:
              method: post
              endpoint: https://process.example/step3
              body:
                step2Id: ${ .step2Result.id }
              timeout:
                minutes: 10
              output: step3Result
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout/workflow
        do:
          - handleWorkflowTimeout:
              call: http
              with:
                method: post
                endpoint: https://logging.example/workflow-timeout
                body:
                  workflowId: ${ $workflow.id }
                  startedAt: ${ $context.workflowStartTime }
                  timedOutAt: ${ now() }
              export:
                as: '$context + { workflowTimedOut: true }'
              then: cleanupAfterTimeout

  - checkRemainingTime:
      try:
        - verifyTimeRemaining:
            if: ${ dateTimeDiff(now(), $context.timeoutAt, "seconds") < 300 }  # Less than 5 minutes left
            then:
              do:
                - skipNonEssentialSteps:
                    set:
                      skipOptionalSteps: true
                    export:
                      as: '$context + { skipOptionalSteps: true }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout/workflow
        do:
          - handleTimeoutDuringCheck:
              export:
                as: '$context + { workflowTimedOut: true }'
              then: cleanupAfterTimeout

  - optionalFinalProcessing:
      if: ${ $context.skipOptionalSteps != true }
      call: http
      with:
        method: post
        endpoint: https://process.example/final-processing
        body:
          step3Id: ${ .step3Result.id }
        timeout:
          minutes: 5
        output: finalResult

  - createFinalResult:
      set:
        result:
          success: ${ $context.workflowTimedOut != true }
          step1: ${ .step1Result }
          step2: ${ .step2Result }
          step3: ${ .step3Result }
          final: ${ .finalResult || null }

  - cleanupAfterTimeout:
      do:
        - getProcessStatus:
            call: http
            with:
              method: get
              endpoint: https://process.example/status
              query:
                step1Id: ${ .step1Result?.id }
                step2Id: ${ .step2Result?.id }
                step3Id: ${ .step3Result?.id }
              output: processStatus
        - cancelRunningProcesses:
            for:
              in: "${ .processStatus.running }"
              each: process
            do:
              - cancelProcess:
                  call: http
                  with:
                    method: post
                    endpoint: https://process.example/cancel/${$process.id}
        - setTimeoutResult:
            set:
              result:
                success: false
                reason: "Workflow exceeded global timeout of 30 minutes"
                completedSteps: ${ .processStatus.completed }
                cancelledSteps: ${ .processStatus.running }
```

## Timeout Strategies for Different Services

### Database Query Timeouts

This example shows appropriate timeout settings for database operations.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: database-timeouts
  version: 1.0.0
do:
  - simpleQuery:
      try:
        - executeQuery:
            call: http
            with:
              method: get
              endpoint: https://db.example/api/items
              query:
                limit: 10
              timeout:
                seconds: 3  # Short timeout for simple queries
              output: queryResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        retry:
          delay:
            milliseconds: 500
          limit:
            attempt:
              count: 2

  - complexQuery:
      try:
        - executeComplexQuery:
            call: http
            with:
              method: post
              endpoint: https://db.example/api/query
              body:
                joins: 3
                filters: [
                  { field: "category", operator: "in", values: ["A", "B", "C"] },
                  { field: "status", operator: "=", value: "active" },
                  { field: "date", operator: ">=", value: "2023-01-01" }
                ]
                sort: [{ field: "date", direction: "desc" }]
              timeout:
                seconds: 10  # Longer timeout for complex query
              output: complexResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        do:
          - simplifyAndRetry:
              call: http
              with:
                method: post
                endpoint: https://db.example/api/query
                body:
                  joins: 1
                  filters: [
                    { field: "status", operator: "=", value: "active" }
                  ]
                  sort: [{ field: "date", direction: "desc" }]
                timeout:
                  seconds: 5
                output: simplifiedResult
              export:
                as: '$context + { usedSimplifiedQuery: true }'

  - analyticalQuery:
      try:
        - executeAnalyticalQuery:
            call: http
            with:
              method: post
              endpoint: https://analytics.example/api/query
              body:
                type: "aggregate"
                groupBy: ["category", "month"]
                metrics: ["count", "sum", "average"]
                filters: ${ .additionalFilters || [] }
              timeout:
                minutes: 2  # Long timeout for analytical queries
              output: analyticalResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        do:
          - requestAsyncProcessing:
              call: http
              with:
                method: post
                endpoint: https://analytics.example/api/jobs
                body:
                  queryType: "aggregate"
                  parameters:
                    groupBy: ["category", "month"]
                    metrics: ["count", "sum", "average"]
                    filters: ${ .additionalFilters || [] }
                  callbackEvent:
                    type: "com.example.analytics.complete"
                    correlationId: ${ $workflow.id }
                output: analyticsJob
              export:
                as: '$context + { analyticsJobId: .analyticsJob.id, usedAsyncAnalytics: true }'

  - waitForAsyncResults:
      if: ${ $context.usedAsyncAnalytics == true }
      listen:
        to:
          one:
            with:
              type: com.example.analytics.complete
              correlationId: ${ $workflow.id }
        for:
          minutes: 15
        output:
          as: asyncResults
      export:
        as: '$context + { analyticalResult: .asyncResults.data }'

  - prepareResponse:
      set:
        result:
          simpleQuery: ${ .queryResult }
          complexQuery: ${ $context.usedSimplifiedQuery ? .simplifiedResult : .complexResult }
          analyticalQuery: ${ $context.analyticalResult || "No analytical results available" }
          usedSimplifiedQuery: ${ $context.usedSimplifiedQuery == true }
          usedAsyncAnalytics: ${ $context.usedAsyncAnalytics == true }
```

### External API Timeouts

This example demonstrates different timeouts for different types of external APIs.

```yaml
document:
  dsl: 1.0.0
  namespace: examples
  name: api-timeouts
  version: 1.0.0
do:
  - callHighReliabilityAPI:
      try:
        - makeCall:
            call: http
            with:
              method: get
              endpoint: https://reliable-api.example/data
              timeout:
                seconds: 2  # Short timeout for high-reliability API
              output: reliableResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        retry:
          delay:
            milliseconds: 500
          backoff:
            exponential: {}
          limit:
            attempt:
              count: 3

  - callThirdPartyAPI:
      try:
        - makeCall:
            call: http
            with:
              method: get
              endpoint: https://third-party.example/api/resource
              timeout:
                seconds: 5  # Medium timeout for third-party API
              output: thirdPartyResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        do:
          - useCachedData:
              call: http
              with:
                method: get
                endpoint: https://cache.example/third-party/latest
                timeout:
                  seconds: 1
                output: cachedThirdParty
              export:
                as: '$context + { usedCache: true, thirdPartyResult: .cachedThirdParty }'

  - callUnreliableAPI:
      try:
        - makeCall:
            call: http
            with:
              method: get
              endpoint: https://unreliable-api.example/data
              timeout:
                seconds: 10  # Longer timeout for unreliable API
              output: unreliableResult
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout
        retry:
          when: ${ $context.retriesAttempted < 2 }  # Only retry twice
          delay:
            seconds: 2
          backoff:
            exponential:
              multiplier: 2
              base: 2
          limit:
            attempt:
              count: 2
        do:
          - trackRetry:
              set:
                retriesAttempted: ${ $context.retriesAttempted || 0 + 1 }
              export:
                as: '$context + { retriesAttempted: ($context.retriesAttempted || 0) + 1 }'
          - generateFallbackData:
              set:
                unreliableResult:
                  success: false
                  data: null
                  message: "API unavailable, using empty data set"
              export:
                as: '$context + { usedFallback: true, unreliableResult: { success: false, data: null, message: "API unavailable, using empty data set" } }'

  - prepareResponse:
      set:
        result:
          reliableData: ${ .reliableResult }
          thirdPartyData: ${ $context.thirdPartyResult || .thirdPartyResult }
          unreliableData: ${ $context.unreliableResult || .unreliableResult }
          usedCache: ${ $context.usedCache == true }
          usedFallback: ${ $context.usedFallback == true }
```

## Best Practices for Timeout Handling

When implementing timeout strategies in Lemline workflows, consider these best practices:

1. **Appropriate Duration**: Set timeout durations appropriate to the operation being performed
2. **Graduated Timeouts**: Use shorter timeouts for quick operations, longer for complex ones
3. **Retry Strategy**: Combine timeouts with appropriate retry strategies
4. **Fallback Logic**: Implement fallback mechanisms when operations timeout
5. **Monitoring**: Log and monitor timeout occurrences to identify patterns
6. **Circuit Breaking**: Consider implementing circuit breakers for frequently timing out services
7. **Global Limits**: Set workflow-level timeouts to prevent indefinite execution
8. **Compensating Actions**: Implement cleanup actions when timeouts occur mid-process
9. **Timeout Prediction**: Monitor remaining time and adjust behavior as deadlines approach
10. **Documentation**: Document expected timeouts for each integration point

## Conclusion

These examples demonstrate various approaches to implementing effective timeout handling in Lemline workflows. By combining appropriate timeout durations with retry strategies, fallback mechanisms, and cleanup actions, you can build resilient workflows that handle external service failures gracefully.

Proper timeout handling ensures that workflows remain responsive even when dependent services are slow or unresponsive, improving overall system reliability and user experience.

For more examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.