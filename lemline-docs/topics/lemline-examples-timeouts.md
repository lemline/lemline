# Timeout Handling Examples

This document provides examples of implementing effective timeout strategies in Lemline workflows. Proper timeout handling is essential for building robust workflows that gracefully handle slow or unresponsive services, preventing workflows from hanging indefinitely and ensuring predictable execution times.

> **Lemline runtime:** only `listen` task timeouts are enforced. Workflow-level timeouts and timeouts on other tasks are accepted but ignored for now. HTTP calls use fixed client limits instead: 15 seconds to connect, 30 seconds per request.

## Run the Examples

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep its worker running. Run the commands from a second
terminal in the setup directory. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline`. Start the
[tutorial shop](lemline-tutorial-order-processing.md#start-the-tutorial-shop) from the root of the Lemline source
checkout; its `delayMs` query parameter makes an answer slow:

```bash
docker compose -f examples/tutorial-services/compose.yaml up -d
```

The event examples also need the tutorial publisher. Save it as `tutorial-publisher.yaml` and register it:

<code-block lang="yaml" src="workflows/reference/tutorial-publisher.yaml"/>

```bash
bin/lemline definition post -f tutorial-publisher.yaml
```

## Basic Timeout Handling

### HTTP Failure Fallback

This example returns the error from a failed HTTP request, including a failure from
the HTTP client's own timeout.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/http-request-with-timeout.yaml"/>

Save it as `http-request-with-timeout.yaml`:

```bash
bin/lemline definition post -f http-request-with-timeout.yaml
bin/lemline instance start examples http-error-fallback --input '{"delayMs":0}'
bin/lemline instance start examples http-error-fallback --input '{"delayMs":40000}'
```

The first instance returns the stock. In the second, the shop waits 40 seconds; the HTTP client gives up after 30 and the catch returns `success: false` with the `runtime` error titled `Socket timeout has expired …`.

### Graduated Timeouts

This example shows graduated timeouts for a cache, a database and a computed fallback. Lemline accepts the timeouts but does not apply them; the fallbacks run when a call fails.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/graduated-timeouts.yaml"/>

Save it as `graduated-timeouts.yaml`:

```bash
bin/lemline definition post -f graduated-timeouts.yaml
bin/lemline instance start examples graduated-timeouts --input '{"itemId":"SKU-2"}'
bin/lemline instance start examples graduated-timeouts --input '{"itemId":"SKU-2","cacheMiss":true}'
bin/lemline instance start examples graduated-timeouts --input '{"itemId":"SKU-2","cacheMiss":true,"databaseDelayMs":4000}'
```

The first instance answers from the cache (`source: cache`), the second from the database after a cache miss (`source: database`). In the third, the database takes 4 seconds: the 3-second timeout is not applied, so the call waits and still answers with `source: database`. Only the failure of a call, not its duration, reaches the next fallback on the current build.

## Timeouts with Event Waiting

These examples receive events from the tutorial publisher on the worker's SQLite channel.

### Event Timeout

This workflow waits up to 30 seconds for a specific process to complete; a real process would get minutes. The
producer stores each status before emitting the event. On timeout, the workflow reads the stored status so it can
still recover a completion sent before the listener was registered.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/event-timeout.yaml"/>

Save it as `event-timeout.yaml`:

```bash
bin/lemline definition post -f event-timeout.yaml
bin/lemline instance start examples event-timeout --input '{"processId":"ORD-1401"}'
```

Publish the completion while it waits:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.process.completed","data":{"processId":"ORD-1401","result":"shipped"}}'
```

The instance completes with `success: true` and the event data. To see the timeout path, reserve an order in the shop,
which stands for the durable status store, and start a listener for it without publishing anything:

```bash
curl -X POST http://127.0.0.1:8787/reservations -d '{"orderId":"ORD-1402","items":[{"id":"SKU-1","quantity":1}]}'
bin/lemline instance start examples event-timeout --input '{"processId":"ORD-1402"}'
```

After 30 to 40 seconds the listener times out, the workflow reads the stored reservation, and completes with
`{"success":true,"status":"reserved","processId":"ORD-1402"}`.

### Multiple Event Timeout

This example demonstrates waiting for multiple events with a timeout.

<code-block lang="yaml" src="workflows/reference/listen-for-all-results.yaml"/>

Save it as `listen-for-all-results.yaml`:

```bash
bin/lemline definition post -f listen-for-all-results.yaml
bin/lemline instance start examples listen-for-all-results
```

Publish both results while it waits:

```bash
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.inventory.checked","data":{"stock":2}}'
bin/lemline instance start examples tutorial-publisher --input '{"type":"com.example.shipping.quoted","data":{"price":15}}'
```

Expect `{"checks":[{"stock":2},{"price":15}]}`. Without both events, the listener would fault with a `timeout` error after five minutes.

## Workflow-Level Timeouts

### Deadline Between Tasks

This example checks a deadline before starting the final task. A task already in
progress continues until it returns or reaches its own timeout.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/global-workflow-timeout.yaml"/>

Save it as `global-workflow-timeout.yaml`:

```bash
bin/lemline definition post -f global-workflow-timeout.yaml
bin/lemline instance start examples deadline-between-tasks --input '{"orderId":"ORD-1403"}'
bin/lemline instance start examples deadline-between-tasks --input '{"orderId":"ORD-1404","deadlineSeconds":1}'
```

The first instance has 30 minutes and finishes: the shop's audit list shows `Finished with 99 units available` for ORD-1403: the ORD-1402 reservation above holds one unit. The second gives itself one second; the 2-second preparation uses it up, so the workflow returns `{"success":false,"reason":"Deadline reached before the final step"}` without calling the final step.

## Timeout Strategies for Different Services

### Database Query Timeouts

This example shows timeout settings for database-style queries, here the shop's catalog. Lemline accepts them but does not apply them.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/database-query-timeouts.yaml"/>

Save it as `database-query-timeouts.yaml`:

```bash
bin/lemline definition post -f database-query-timeouts.yaml
bin/lemline instance start examples database-timeouts --input '{}'
bin/lemline instance start examples database-timeouts --input '{"slowQueryMs":2000}'
```

Both instances complete with `queryResult: 7` and `complexResult: ["SKU-1"]`. In the second, the complex query takes 2 seconds: its 1-second timeout is not applied, so the fallback query does not run.

### External API Timeouts

This example declares different timeouts for different types of external APIs. Lemline accepts them but does not apply them.

<code-block lang="yaml" src="workflows/lemline-examples-timeouts/external-api-timeouts.yaml"/>

Save it as `external-api-timeouts.yaml`:

```bash
bin/lemline definition post -f external-api-timeouts.yaml
bin/lemline instance start examples api-timeouts --input '{}'
bin/lemline instance start examples api-timeouts --input '{"thirdPartyDelayMs":6000}'
```

Both instances complete with the live product `Desk lamp` and `source: live`: the second waits 6 seconds for the shop, beyond its 5-second timeout. The cached fallback only runs if the call fails, for example after the 30-second client limit. Stop the shop when you are done:

```bash
docker compose -f examples/tutorial-services/compose.yaml down
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
