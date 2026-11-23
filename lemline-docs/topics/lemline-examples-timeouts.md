# Timeout Handling Examples

This document provides examples of implementing effective timeout strategies in Lemline workflows. Proper timeout handling is essential for building robust workflows that gracefully handle slow or unresponsive services, preventing workflows from hanging indefinitely and ensuring predictable execution times.

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

## Basic Timeout Handling

### HTTP Failure Fallback

This example returns the error from a failed HTTP request, including a failure from
the HTTP client's own timeout.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/http-request-with-timeout.yaml"/>

### Graduated Timeouts

This example shows how to implement graduated timeouts for different types of requests.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/graduated-timeouts.yaml"/>

## Timeouts with Event Waiting

Run these examples with the Lemline runner connected to a message broker.

### Event Timeout

This workflow waits up to five minutes for a specific process to complete. The
producer stores each completion before emitting the event. On timeout, the workflow
reads the stored status so it can still recover a completion sent before the listener
was registered.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/event-timeout.yaml"/>

### Multiple Event Timeout

This example demonstrates waiting for multiple events with a timeout.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

## Workflow-Level Timeouts

### Deadline Between Tasks

This example checks a deadline before starting the final task. A task already in
progress continues until it returns or reaches its own timeout.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/global-workflow-timeout.yaml"/>

## Timeout Strategies for Different Services

### Database Query Timeouts

This example shows appropriate timeout settings for database operations.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/database-query-timeouts.yaml"/>

### External API Timeouts

This example demonstrates different timeouts for different types of external APIs.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/external-api-timeouts.yaml"/>

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
