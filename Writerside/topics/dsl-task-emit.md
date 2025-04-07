# Emit

## Purpose

The `Emit` task allows workflows to publish [CloudEvents](https://cloudevents.io/) to event brokers or messaging
systems.

This facilitates communication and coordination between different components and services. With the `Emit` task,
workflows can seamlessly integrate with event-driven architectures, enabling real-time processing, event-driven
decision-making, and reactive behavior based on external systems consuming these events.

## Usage Example

```yaml
document:
  dsl: '1.0.0' # Assuming alpha5 or later based on reference example
  namespace: test
  name: emit-example
  version: '0.1.0'
do:
  - placeOrder:
      # ... logic to process an order ...
      then: emitOrderPlacedEvent
  - emitOrderPlacedEvent:
      emit:
        event:
          # Defines the event to be emitted
          with:
            source: https://petstore.com/orders
            type: com.petstore.order.placed.v1
            subject: "order-123"
            # Event data payload, often constructed using runtime expressions
            data:
              client:
                # Assuming client info is in the context or previous task output
                firstName: "${ $context.customer.first }"
                lastName: "${ $context.customer.last }"
              # Assuming items are in the context or previous task output
              items: "${ $context.orderItems }"
```

In this example, after an order is processed, the `emitOrderPlacedEvent` task publishes a CloudEvent with specific
attributes (`source`, `type`, `subject`) and a data payload constructed from the workflow context.

## Configuration Options

### `emit` (Emit, Required)

This object defines the event to be published.

* **`event`** (`eventProperties`, Required): Specifies the properties of the CloudEvent to emit. This typically
  includes:
    * `with`: An object containing standard CloudEvent attributes (like `type`, `source`, `subject`, `id`, `time`,
      `datacontenttype`, `dataschema`) and the event `data` payload. Values can often be defined
      using [Runtime Expressions](dsl-runtime-expressions.md) to include dynamic information from the workflow context
      or task input.

### Data Flow

<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**: 
*   The `Emit` task primarily uses its configuration (`emit.event.with`) to construct the event to be sent. The `transformedInput` to the `Emit` task is available for use within runtime expressions inside the `emit.event.with` definition.
*   The `Emit` task itself typically does not produce a meaningful `rawOutput`. If specific output is needed, it must be explicitly defined using `output.as`.

### Flow Control

<include from="_common-task-flow_control.md" element-id="common-flow-control"/> 