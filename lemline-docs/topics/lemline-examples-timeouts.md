# Timeout Handling Examples

This document provides examples of implementing effective timeout strategies in Lemline workflows. Proper timeout handling is essential for building robust workflows that gracefully handle slow or unresponsive services, preventing workflows from hanging indefinitely and ensuring predictable execution times.

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

## Basic Timeout Handling

### HTTP Request with Timeout

This example demonstrates a simple HTTP request with a timeout and appropriate error handling.

```yaml
document:
  dsl: 1.0.3
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
            output:
              as: '${ $input + { "apiResponse": . } }'
            timeout:
              after:
                seconds: 5  # Request will timeout after 5 seconds
      catch:
        as: failure
        when: ${ ($failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/timeout") or ($failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/communication") }
        do:
          - selectHandler:
              switch:
                - case1:
                    then: handle1
                    when: ${ $failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/timeout" }
                - case2:
                    then: handle2
                    when: ${ $failure.type == "https://open-workflow-specification.org/spec/1.0.0/errors/communication" }
          - handle1:
              do:
                - handleTimeout:
                    set:
                      result:
                        success: false
                        reason: API call timed out
                        details: Service did not respond within 5 seconds
              then: exit
          - handle2:
              do:
                - handleCommunicationError:
                    set:
                      result:
                        success: false
                        reason: Communication error
                        details: ${ $failure }
              then: exit
```

### Graduated Timeouts

This example shows how to implement graduated timeouts for different types of requests.

```yaml
document:
  dsl: 1.0.3
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
              endpoint: ${ "https://cache.example/lookup/\(.itemId)" }
            output:
              as: '${ $input + { "cacheResult": . } }'
            timeout:
              after:
                milliseconds: 500  # Very short timeout for cache lookup
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        as: timeoutError
        do:
          - setDefaultCache:
              set:
                cacheResult: null
                cacheHit: false
              export:
                as: '${ $context + { cacheHit: false } }'

  - conditionalDatabaseCall:
      if: ${ $context.cacheHit != true }
      try:
        - callDatabaseService:
            call: http
            with:
              method: get
              endpoint: ${ "https://db.example/items/\(.itemId)" }
            export:
              as: '${ $context + { dbResult: .dbResult, dbLookupSuccess: true } }'
            output:
              as: '${ $input + { "dbResult": . } }'
            timeout:
              after:
                seconds: 3  # Longer timeout for database lookup
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        as: dbTimeoutError
        do:
          - saveOutcome:
              set: ${ . }
              export:
                as: '$context + { dbLookupSuccess: false, dbTimeoutError: $dbTimeoutError }'

  - conditionalSlowProcessing:
      if: ${ $context.dbLookupSuccess != true }
      try:
        - callFallbackService:
            call: http
            with:
              method: get
              endpoint: ${ "https://fallback.example/compute/\(.itemId)" }
            export:
              as: '${ $context + { fallbackResult: .fallbackResult, fallbackSuccess: true } }'
            output:
              as: '${ $input + { "fallbackResult": . } }'
            timeout:
              after:
                seconds: 10  # Longest timeout for complex computation
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        as: fallbackTimeoutError
        do:
          - saveOutcome:
              set: ${ . }
              export:
                as: '$context + { fallbackSuccess: false, fallbackTimeoutError: $fallbackTimeoutError }'

  - prepareResponse:
      switch:
        - cachedResult:
            when: ${ $context.cacheHit == true }
            then: prepareResponse-cachedResult
        - dbResult:
            when: ${ $context.dbLookupSuccess == true }
            then: prepareResponse-dbResult
        - fallbackResult:
            when: ${ $context.fallbackSuccess == true }
            then: prepareResponse-fallbackResult
        - default:
            then: prepareResponse-default
  - prepareResponse-cachedResult:
      set:
        result:
          source: "cache"
          data: ${ $context.cacheResult }
      then: prepareResponse-done
  - prepareResponse-dbResult:
      set:
        result:
          source: "database"
          data: ${ $context.dbResult }
      then: prepareResponse-done
  - prepareResponse-fallbackResult:
      set:
        result:
          source: "fallback"
          data: ${ $context.fallbackResult }
      then: prepareResponse-done
  - prepareResponse-default:
      set:
        result:
          source: "default"
          data: null
          error: "All data sources timed out"
      then: prepareResponse-done
  - prepareResponse-done:
      set: ${ . }
```

## Timeouts with Event Waiting

### Event Timeout

This example demonstrates how to implement a timeout when waiting for an external event.

```yaml
document:
  dsl: 1.0.3
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
          processId: ${ $workflow.id }
          data: ${ .inputData }
      export:
        as: '${ $context + { processId: .process.id } }'

      output:
        as: '${ $input + { "process": . } }'
  - waitForCompletion:
      try:
        - waitForCompletionOperation:
            listen:
              to:
                one:
                  with:
                    type: com.example.process.completed
                    correlationId: ${ $context.processId }
            output:
              as: completionEvent
            timeout:
              after:
                minutes: 5 # If event doesn't arrive within 5 minutes, timeout occurs
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        do:
          - handleEventTimeout:
              do:
                - checkProcessStatus:
                    call: http
                    with:
                      method: get
                      endpoint: ${ "https://process.example/status/\($context.processId)" }
                    output:
                      as: '${ $input + { "processStatus": . } }'
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
            try:
              - retryEventWaitOperation:
                  listen:
                    to:
                      one:
                        with:
                          type: com.example.process.completed
                          correlationId: ${ $context.processId }
                  output:
                    as: completionEvent
                  timeout:
                    after:
                      minutes: 10 # Extended timeout
            catch:
              errors:
                with:
                  type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
              do:
                - forceCancel:
                    set: ${ . }
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
              endpoint: ${ "https://process.example/cancel/\($context.processId)" }
            output:
              as: '${ $input + { "cancellationResult": . } }'
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
  dsl: 1.0.3
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
                processId: '${ "a-" + $workflow.id }'
            export:
              as: '${ $context + { processAId: .processA.id } }'
            output:
              as: '${ $input + { "processA": . } }'
        - startProcessB:
            call: http
            with:
              method: post
              endpoint: https://process.example/b/start
              body:
                processId: '${ "b-" + $workflow.id }'
            export:
              as: '${ $context + { processBId: .processB.id } }'

            output:
              as: '${ $input + { "processB": . } }'
  - waitForProcesses:
      try:
        - waitForProcessesOperation:
            listen:
              to:
                all:
                  - with:
                      type: com.example.process.a.completed
                      correlationId: '${ $context.processAId }'
                  - with:
                      type: com.example.process.b.completed
                      correlationId: '${ $context.processBId }'
            output:
              as: completionEvents
            timeout:
              after:
                minutes: 10 # Timeout if both events don't arrive within 10 minutes
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        do:
          - handlePartialCompletion:
              call: http
              with:
                method: post
                endpoint: https://process.example/check-status
                body:
                  processes: [{id: '${ $context.processAId }', type: "a"}, {id: '${ $context.processBId }', type: "b"}]
              export:
                as: '${ $context + { processStatuses: .processStatuses } }'
              output:
                as: '${ $input + { "processStatuses": . } }'
          - cleanupUnfinishedProcesses:
              for:
                in: "${ $context.processStatuses.unfinished }"
                each: process
              do:
                - cancelProcess:
                    call: http
                    with:
                      method: post
                      endpoint: ${ "https://process.example/\($process.type)/cancel/\($process.id)" }
          - setPartialResult:
              set:
                result:
                  success: false
                  reason: "Partial completion - timeout occurred"
                  completed: '${ $context.processStatuses.finished }'
                  cancelled: '${ $context.processStatuses.unfinished }'
              then: exit

  - combineResults:
      set:
        result:
          success: true
          processAResult: '${ .completionEvents.processAEvent.data }'
          processBResult: '${ .completionEvents.processBEvent.data }'
```

## Workflow-Level Timeouts

### Global Workflow Timeout

This example demonstrates implementing a global timeout for an entire workflow.

```yaml
document:
  dsl: 1.0.3
  namespace: examples
  name: global-workflow-timeout
  version: 1.0.0
do:
  - setupMonitor:
      set:
        workflowStartTime: '${ now }'
        timeoutAt: '${ (now + 1800) | todateiso8601 }'
      export:
        as: '${ $context + {workflowStartTime: (now | todateiso8601), timeoutAt: ((now + 1800) | todateiso8601)} }'

  - longRunningProcess:
      try:
        - longRunningProcessOperation:
            do:
              - step1:
                  call: http
                  with:
                    method: post
                    endpoint: https://process.example/step1
                  output:
                    as: '${ $input + { "step1Result": . } }'
                  timeout:
                    after:
                      minutes: 5
              - step2:
                  call: http
                  with:
                    method: post
                    endpoint: https://process.example/step2
                    body:
                      step1Id: '${ .step1Result.id }'
                  output:
                    as: '${ $input + { "step2Result": . } }'
                  timeout:
                    after:
                      minutes: 10
              - step3:
                  call: http
                  with:
                    method: post
                    endpoint: https://process.example/step3
                    body:
                      step2Id: '${ .step2Result.id }'
                  output:
                    as: '${ $input + { "step3Result": . } }'
                  timeout:
                    after:
                      minutes: 10
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout/workflow
        do:
          - handleWorkflowTimeout:
              call: http
              with:
                method: post
                endpoint: https://logging.example/workflow-timeout
                body:
                  workflowId: '${ $workflow.id }'
                  startedAt: '${ $context.workflowStartTime }'
                  timedOutAt: '${ now }'
              export:
                as: '${ $context + { workflowTimedOut: true } }'
              then: cleanupAfterTimeout

  - checkRemainingTime:
      try:
        - verifyTimeRemaining:
            do:
              - invoke:
                  set: ${ . }
                  if: '${ dateTimeDiff(now(), $context.timeoutAt, "seconds") < 300 }  # Less than 5 minutes left'
              - finish:
                  do:
                    - skipNonEssentialSteps:
                        set:
                          skipOptionalSteps: true
                        export:
                          as: '$context + { skipOptionalSteps: true }'
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout/workflow
        do:
          - handleTimeoutDuringCheck:
              set: ${ . }
              export:
                as: '${ $context + { workflowTimedOut: true } }'
              then: cleanupAfterTimeout
  - optionalFinalProcessing:
      if: '${ $context.skipOptionalSteps != true }'
      call: http
      with:
        method: post
        endpoint: https://process.example/final-processing
        body:
          step3Id: '${ .step3Result.id }'
      output:
        as: '${ $input + { "finalResult": . } }'
      timeout:
        after:
          minutes: 5
  - createFinalResult:
      set:
        result:
          success: '${ $context.workflowTimedOut != true }'
          step1: '${ .step1Result }'
          step2: '${ .step2Result }'
          step3: '${ .step3Result }'
          final: '${ .finalResult // null }'

  - cleanupAfterTimeout:
      do:
        - getProcessStatus:
            call: http
            with:
              method: get
              endpoint: https://process.example/status
              query:
                step1Id: '${ .step1Result?.id }'
                step2Id: '${ .step2Result?.id }'
                step3Id: '${ .step3Result?.id }'
            output:
              as: '${ $input + { "processStatus": . } }'
        - cancelRunningProcesses:
            for:
              in: "${ .processStatus.running }"
              each: process
            do:
              - cancelProcess:
                  call: http
                  with:
                    method: post
                    endpoint: ${ "https://process.example/cancel/\($process.id)" }
        - setTimeoutResult:
            set:
              result:
                success: false
                reason: "Workflow exceeded global timeout of 30 minutes"
                completedSteps: '${ .processStatus.completed }'
                cancelledSteps: '${ .processStatus.running }'
timeout:
  after:
    minutes: 30    # Entire workflow must complete within 30 minutes
```

## Timeout Strategies for Different Services

### Database Query Timeouts

This example shows appropriate timeout settings for database operations.

```yaml
document:
  dsl: 1.0.3
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
                limit: '10'
            output:
              as: '${ $input + { "queryResult": . } }'
            timeout:
              after:
                seconds: 3  # Short timeout for simple queries
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
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
                filters: [{field: "category", operator: "in", values: ["A", "B", "C"]}, {field: "status", operator: "=",
                        value: "active"}, {field: "date", operator: ">=", value: "2023-01-01"}]
                sort: [{field: "date", direction: "desc"}]
            output:
              as: '${ $input + { "complexResult": . } }'
            timeout:
              after:
                seconds: 10  # Longer timeout for complex query
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        do:
          - simplifyAndRetry:
              call: http
              with:
                method: post
                endpoint: https://db.example/api/query
                body:
                  joins: 1
                  filters: [{field: "status", operator: "=", value: "active"}]
                  sort: [{field: "date", direction: "desc"}]
              export:
                as: '${ $context + { usedSimplifiedQuery: true } }'

              output:
                as: '${ $input + { "simplifiedResult": . } }'
              timeout:
                after:
                  seconds: 5
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
                filters: '${ .additionalFilters // [] }'
            output:
              as: '${ $input + { "analyticalResult": . } }'
            timeout:
              after:
                minutes: 2  # Long timeout for analytical queries
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
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
                    filters: '${ .additionalFilters // [] }'
                  callbackEvent:
                    type: "com.example.analytics.complete"
                    correlationId: '${ $workflow.id }'
              export:
                as: '${ $context + { analyticsJobId: .analyticsJob.id, usedAsyncAnalytics: true } }'

              output:
                as: '${ $input + { "analyticsJob": . } }'
  - waitForAsyncResults:
      if: '${ $context.usedAsyncAnalytics == true }'
      listen:
        to:
          one:
            with:
              type: com.example.analytics.complete
              correlationId: '${ $workflow.id }'
      export:
        as: '${ $context + { analyticalResult: .asyncResults.data } }'

      timeout:
        after:
          minutes: 15
      output:
        as: asyncResults
  - prepareResponse:
      set:
        result:
          simpleQuery: '${ .queryResult }'
          complexQuery: '${ if $context.usedSimplifiedQuery then .simplifiedResult else .complexResult end }'
          analyticalQuery: '${ $context.analyticalResult // "No analytical results available" }'
          usedSimplifiedQuery: '${ $context.usedSimplifiedQuery == true }'
          usedAsyncAnalytics: '${ $context.usedAsyncAnalytics == true }'
```

### External API Timeouts

This example demonstrates different timeouts for different types of external APIs.

```yaml
document:
  dsl: 1.0.3
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
            output:
              as: '${ $input + { "reliableResult": . } }'
            timeout:
              after:
                seconds: 2  # Short timeout for high-reliability API
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
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
            output:
              as: '${ $input + { "thirdPartyResult": . } }'
            timeout:
              after:
                seconds: 5  # Medium timeout for third-party API
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
        do:
          - useCachedData:
              call: http
              with:
                method: get
                endpoint: https://cache.example/third-party/latest
              export:
                as: '${ $context + { usedCache: true, thirdPartyResult: .cachedThirdParty } }'

              output:
                as: '${ $input + { "cachedThirdParty": . } }'
              timeout:
                after:
                  seconds: 1
  - callUnreliableAPI:
      try:
        - makeCall:
            call: http
            with:
              method: get
              endpoint: https://unreliable-api.example/data
            output:
              as: '${ $input + { "unreliableResult": . } }'
            timeout:
              after:
                seconds: 10  # Longer timeout for unreliable API
      catch:
        errors:
          with:
            type: https://open-workflow-specification.org/spec/1.0.0/errors/timeout
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
                retriesAttempted: ${ ($context.retriesAttempted // 0) + 1 }
              export:
                as: '${ $context + { retriesAttempted: (($context.retriesAttempted // 0) + 1)} }'
          - generateFallbackData:
              set:
                unreliableResult:
                  success: false
                  data: null
                  message: "API unavailable, using empty data set"
              export:
                as: '${ $context + { usedFallback: true, unreliableResult: { success: false, data: null, message: "API unavailable,
                  using empty data set" } } }'

  - prepareResponse:
      set:
        result:
          reliableData: ${ .reliableResult }
          thirdPartyData: ${ $context.thirdPartyResult // .thirdPartyResult }
          unreliableData: ${ $context.unreliableResult // .unreliableResult }
          usedCache: ${ $context.usedCache == true }
          usedFallback: ${ $context.usedFallback == true }
```

## Best Practices for Timeout Handling

When implementing timeout strategies in Lemline workflows, consider these best practices:

1. **Appropriate Duration**: Set timeout durations appropriate to the operation being performed (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
2. **Graduated Timeouts**: Use shorter timeouts for quick operations, longer for complex ones (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
3. **Retry Strategy**: Combine timeouts with appropriate retry strategies
4. **Fallback Logic**: Implement fallback mechanisms when operations timeout
5. **Monitoring**: Log and monitor timeout occurrences to identify patterns
6. **Circuit Breaking**: Consider implementing circuit breakers for frequently timing out services
7. **Global Limits**: Set workflow-level timeouts to prevent indefinite execution (Lemline only enforces timeouts on `listen` tasks; HTTP calls use fixed limits of 15 s to connect and 30 s per request)
8. **Compensating Actions**: Implement cleanup actions when timeouts occur mid-process
9. **Timeout Prediction**: Monitor remaining time and adjust behavior as deadlines approach
10. **Documentation**: Document expected timeouts for each integration point

## Conclusion

These examples demonstrate various approaches to implementing effective timeout handling in Lemline workflows. By combining appropriate timeout durations with retry strategies, fallback mechanisms, and cleanup actions, you can build resilient workflows that handle external service failures gracefully.

Proper timeout handling ensures that workflows remain responsive even when dependent services are slow or unresponsive, improving overall system reliability and user experience.

For more examples of error handling and resilience patterns, see the [Error Handling Examples](lemline-examples-error-handling.md) document.