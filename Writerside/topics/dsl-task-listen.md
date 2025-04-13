<!-- Examples are validated -->

# Listen

## Purpose

The `Listen` task pauses the workflow execution until one or more specific external events are received, based on
defined conditions. It allows workflows to react to asynchronous occurrences from external systems or other parts of the
application.

This is crucial for building event-driven workflows where progression depends on external signals.

## Usage Example

```yaml
document:
  dsl: '1.0.0' # Assuming alpha5 or later based on reference example
  namespace: examples
  name: listen-for-vitals
  version: '1.0.0'
do:
  - startMonitoring:
    # ... task that initiates monitoring ...
  - waitForVitalSignAlert:
      listen:
        to:
          any: # Complete when any of the following conditions are met
            - with: # Condition 1: High Temperature
                source: "http://vitals-service"
                type: "com.fake-hospital.vitals.measurements.temperature"
                data: '${ .temperature > 38 }'
            - with: # Condition 2: Abnormal BPM
                source: "http://vitals-service"
                type: "com.fake-hospital.vitals.measurements.bpm"
                data: '${ .bpm < 60 or .bpm > 100 }'
        read: data # Read only the event data (default)
      timeout:
        after: PT1H
      then: processAlert
  - processAlert:
    # ... task that handles the alert based on the received event data ...
    # The task output here is an array containing the event(s) that met the condition
```

In this example, the workflow pauses at `waitForVitalSignAlert` until an event matching either the high temperature
condition or the abnormal BPM condition arrives, or until the 1-hour timeout is reached. The output will be an array
containing the data of the event(s) that triggered completion.

## Configuration Options

### `listen` (Listen, Required)

This object defines the core parameters for the listening behavior.

* **`to`** (`eventConsumptionStrategy`, Required): Configures how event conditions are defined and correlated. Common
  strategies include:
    * `any`: Defines a list of conditions (using `with`). The task completes as soon as *any single event matching any*
      listed condition is received.
        * **Use Case**: Waiting for one of several possible outcomes or signals (e.g., 'approved' OR 'rejected', '
          payment succeeded' OR 'payment failed').
        * **Example**:
          ```yaml
          listen:
            to:
              any:
                - with: { type: com.example.approval } # Match event type
                - with: { source: /orders/urgent } # Match event source
          ```
    * `all`: Defines a list of conditions (using `with`). The task completes only when *at least one event matching
      each* listed condition has been received.
        * **Use Case**: Waiting for multiple distinct signals or acknowledgments before proceeding (e.g., confirmation
          from inventory AND confirmation from shipping).
        * **Example**:
          ```yaml
          listen:
            to:
              all:
                - with: { type: inventory.checked }
                - with: { type: shipping.ready }
          ```
    * `one`: Defines a *single* condition (using `with`). The task completes only when an event matching this specific
      condition is received.
        * **Use Case**: Waiting for a specific, singular event (e.g., 'order shipped' notification, 'user verified'
          signal).
        * **Example**:
          ```yaml
          listen:
            to:
              one:
                with: { type: user.verified, subject: "user-123" } 
          ```
    * Each condition is typically defined using a `with` object specifying:
        * CloudEvent attributes to match (e.g., `type`, `source`).
        * An optional `data` property containing a [Runtime Expression](dsl-runtime-expressions.md) evaluated against
          the *event data* for further filtering.
    * The strategy may also include an optional `until` clause, defining a separate event condition.
        * **Use Case**: Providing an alternative, event-based exit condition for the listening state, often used for
          cancellation or timeouts triggered by specific events (e.g., 'order cancelled', 'timeout notification').
          If an event matching the `until` condition is received, the `Listen` task terminates *immediately*, regardless
          of whether the main `any`/`all`/`one` conditions were satisfied.
        * Events matching the `until` condition are **not** included in the task's output array.
        * **Example**:
          ```yaml
          listen:
            to:
              any: # Wait for approval OR rejection
                - with: { type: com.example.approval }
                - with: { type: com.example.rejection }
              until: # But stop immediately if a cancellation event arrives
                with: { type: com.example.cancellation }
          ```
    * *(Note: The exact structure for defining `any`, `all`, `one`, and `until` depends on the specific definition of
      the `eventConsumptionStrategy` type in the full DSL reference.)*

* **`read`** (String - `data` | `envelope` | `raw`, Optional, Default: `data`): Specifies what part of the consumed
  event(s) should be included in the task's output array:
    * `data`: Include only the event's data payload.
    * `envelope`: Include the full event envelope (context attributes + data).
    * `raw`: Include the event's raw data as received.

* **`foreach`** (`subscriptionIterator`, Optional): If
  specified, configures how to process each consumed event individually using a sub-flow (often defined in
  `foreach.output.as` or a `do` block if the iterator type allows). Consumed events are processed sequentially (FIFO).

* **`timeout`** (String - ISO 8601 Duration, Optional): Specifies the maximum time to wait for the required events based
  on the `listen.to` strategy. If the timeout is reached before the conditions are met, the task **must** fault with a
  `Timeout` error (`https://serverlessworkflow.io/spec/1.0.0/errors/timeout`).

### Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>

**Note**:

* The `transformed input` to the `Listen` task is available for use within the `data` expressions defined under
  `listen.to` (e.g., referencing `$context`).
* The `raw output` of the `Listen` task is **always** a sequentially ordered array containing the content specified by
  `listen.read` (e.g., event data, envelope, or raw) for **all** the event(s) consumed to satisfy the `listen.to`
  condition.
* If `foreach` is used, the transformation configured within the iterator (e.g., `foreach.output.as`) is applied to each
  item *before* it's added to the final output array.
* Standard `output.as` and `export.as` process this resulting `rawOutput` array.

> [!WARNING]
> Events consumed solely to satisfy an `until` clause should **not** be included in the task's output array.

### Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**:
*   The `if` condition is evaluated *before* the task starts listening. If false, the task is skipped, and its `then` directive is followed immediately.
*   The `then` directive is followed only *after* the required event(s) are successfully received (and processed, if `foreach` is used) before the `timeout`. If the task times out, it faults, and `then` is *not* followed. 