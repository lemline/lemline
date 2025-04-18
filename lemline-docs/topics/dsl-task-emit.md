
<!-- Examples are validated -->

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
use:
  secrets:
    - orderServiceApiKey
  authentications:
    orderServiceAuth:
      bearer:
        token: ${ $secrets.orderServiceApiKey }
do:
  - placeOrder:
      call: http
      with:
        method: POST
        endpoint:
          uri: "http://orders-service/api/v1/orders"
          authentication:
            use: orderServiceAuth
        headers:
          Content-Type: "application/json"
        body:
          customerId: ${ .customerId }
          items: ${ .items }
      output:
        as: orderResult
      then: emitOrderPlacedEvent
      catch:
        errors:
          with:
            type: "*"
        then: handleOrderError
  - emitOrderPlacedEvent:
      emit:
        event:
          with:
            source: "https://petstore.com/orders"
            type: "com.petstore.order.placed.v1"
            subject: "${ .orderResult.orderId }"
            data:
              client:
                firstName: "${ $context.customer.first }"
                lastName: "${ $context.customer.last }"
              orderId: "${ .orderResult.orderId }"
              items: "${ .orderResult.items }"
      output:
        as: eventResult
  - handleOrderError:
      emit:
        event:
          with:
            source: "https://petstore.com/orders"
            type: "com.petstore.order.error.v1"
            subject: "order-error"
            data:
              error: "${ .error.message }"
              customerId: "${ .customerId }"