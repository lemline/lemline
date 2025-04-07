---
title: AsyncAPI Call
---

# AsyncAPI Call Task (`call: asyncapi`)

## Purpose

The AsyncAPI Call task enables workflows to interact with message brokers and event-driven services described by an [AsyncAPI](https://www.asyncapi.com/) specification document.

This allows workflows to publish messages to channels or subscribe to messages from channels defined in the AsyncAPI document.

## Usage Examples

### Example: Publishing a Message

```yaml
document:
  dsl: '1.0.0' # Assuming alpha5 or later based on reference example
  namespace: test
  name: asyncapi-publish-example
  version: '0.1.0'
do:
  - publishGreeting:
      call: asyncapi
      with:
        # Reference to the AsyncAPI document
        document:
          endpoint: https://broker.example.com/docs/asyncapi.json
        # ID of the operation (e.g., sendGreeting) defined in AsyncAPI doc
        operationId: sendGreeting 
        # Optional: Specify server connection details
        server:
          name: productionBroker
          variables:
            environment: prod
        # Define the message to publish
        message:
          payload:
            greeting: "Hello from workflow ${ $workflow.id }"
          headers:
            traceId: "${ $context.traceId }"
      # Output typically confirms publish success/failure, specifics vary
      then: afterPublish
  - afterPublish:
      # ... 
```

### Example: Subscribing to Messages

```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: asyncapi-subscribe-example
  version: '0.1.0'
do:
  - subscribeToChatRoom:
      call: asyncapi
      with:
        document:
          endpoint: https://chat.example.com/api/asyncapi.yaml
        operationId: receiveChatMessages # Operation ID for subscribing
        # Optional: Specify protocol if needed to select server
        protocol: ws 
        subscription:
          # Optional: Filter messages based on payload content
          filter: '${ .roomId == $context.targetRoomId }' 
          consume:
            # Define consumption limits (e.g., max messages or time)
            amount: 10 # Max 10 messages
            for: { minutes: 5 } # Or max 5 minutes
          # Optional: Process each consumed message
          foreach:
             # ... iterator definition (similar to Listen foreach) ... 
      # Output contains consumed messages (details depend on foreach)
      then: afterSubscription
  - afterSubscription:
      # ...
```

## Additional Examples

### Example: Subscription with `foreach` Processing

```yaml
do:
  - processOrderUpdates:
      call: asyncapi
      with:
        document: { endpoint: file://asyncapi/orders.yaml }
        operationId: onOrderUpdate # Subscribe operation
        server: { name: productionKafka }
        subscription:
          # Define how to process each received message
          foreach: 
            items: "." # The input is the array of consumed messages
            iterator: msg # Variable name for each message in the loop
            do: # Workflow steps to execute for each message
              - logUpdate:
                  call: logMessage
                  with:
                    message: "Received order update: ${ .msg.payload.orderId }"
              - checkStatus:
                  switch:
                    - if: "${ .msg.payload.status == 'SHIPPED' }"
                      then: notifyShipping
            # Define the output of the foreach loop (and thus the task)
            output:
              processedCount: "${ count(.) }" # Count of messages processed
              lastOrderId: "${ .[-1]?.msg.payload.orderId }" # ID of the last message
```

### Example: Publish with Authentication

```yaml
# Assume 'myBrokerAuth' is defined in use.authentications
do:
  - sendSecureEvent:
      call: asyncapi
      with:
        document: { endpoint: file://asyncapi/secure_events.yaml }
        operationId: publishSecureEvent
        server: { name: secureMQTT }
        # Reference the authentication definition
        authentication: myBrokerAuth 
        message:
          payload:
            eventId: "${ $uuid() }"
            data: "${ .sensitiveData }"
      then: confirmEventSent
```

## Configuration Options

The configuration for an AsyncAPI call is provided within the `with` property of the `call: asyncapi` task.

### `with` (Object, Required)

*   **`document`** (Object, Required): Defines the location of the AsyncAPI specification document (JSON or YAML). Contains:
    *   `endpoint` (Object, Required): Specifies the location with `uri` (String | Object, Required) and optional `authentication` (String | Object).
*   **`operationId`** (String, Required): The ID (`operationId`) of the specific operation (publish or subscribe) to invoke, as defined within the AsyncAPI `document`.
*   **`server`** (Object, Optional): Configuration for connecting to a specific server defined in the AsyncAPI document. If omitted, the runtime selects a suitable server based on the operation and `protocol`. Contains:
    *   `name` (String, Required): The name of the server (must match a server name defined in the AsyncAPI document under the specified operation/channel).
    *   `variables` (Object, Optional): A key/value map to override [Server Variables](https://www.asyncapi.com/docs/reference/specification/v3.0.0#serverVariableObject) defined in the AsyncAPI document for the selected server.
*   **`protocol`** (String, Optional): The protocol to use, helping select the target server if `server` is not specified or if multiple servers support the operation. Supported values include: `amqp`, `amqp1`, `anypointmq`, `googlepubsub`, `http`, `ibmmq`, `jms`, `kafka`, `mercure`, `mqtt`, `mqtt5`, `nats`, `pulsar`, `redis`, `sns`, `solace`, `sqs`, `stomp`, `ws`.
*   **`message`** (Object, Conditionally Required): Defines the message to be published. Required if the `operationId` represents a *publish* action. Contains details matching the [AsyncAPI Message Object](https://www.asyncapi.com/docs/reference/specification/v3.0.0#messageObject), such as:
    *   `payload` (Any, Optional): The main content/body of the message.
    *   `headers` (Object, Optional): Application-specific headers for the message.
    *   `correlationId` (String, Optional): ID used for message correlation.
    *   (Other properties like `contentType`, `name`, `title`, `summary`, `description`, `tags`, `externalDocs`, `bindings`, `examples`, `traits` may be supported depending on runtime capabilities).
*   **`subscription`** (Object, Conditionally Required): Defines how to subscribe to and consume messages. Required if the `operationId` represents a *subscribe* action. Contains:
    *   `filter` (String, Optional): Runtime expression evaluated against incoming messages to select which ones to consume.
    *   `consume` (Object, Required): Defines the lifetime and limits of the subscription. Contains *one* of `amount` or `while`/`until`, plus optional `for`:
        *   `amount` (Integer, Optional): Maximum number of messages to consume.
        *   `while` (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md). Keep consuming messages as long as this expression evaluates to true.
        *   `until` (String, Optional): A [Runtime Expression](dsl-runtime-expressions.md). Keep consuming messages until this expression evaluates to true.
        *   `for` (String | Object, Optional): Maximum duration to subscribe/wait for messages (ISO 8601 duration string or [Duration Object](TODO: Link to Duration object page if exists)). This acts as a timeout for the consume condition.
    *   `foreach` (Object, Optional): Defines how to process each consumed message individually. Contains:
        *   `item` (String, Optional, Default: `item`): Variable name for the current message being processed.
        *   `at` (String, Optional, Default: `index`): Variable name for the index of the current message.
        *   `do` (List<Map<String, Task>>, Optional): A list of tasks to execute for each message.
        *   `output` (Object, Optional): Configures the transformation of the result from the `do` block for each item.
        *   `export` (Object, Optional): Configures how the result from the `do` block updates the main workflow context.
*   **`authentication`** (String | Object, Optional): Authentication details (inline definition or reference by name from `use.authentications`) needed to connect to the message broker/server.

### Authentication

The `with` object contains an optional `authentication` property to specify credentials needed to connect to the message broker or server defined by the AsyncAPI document. You can use an inline definition or reference a named policy from `use.authentications`.

See the main [Authentication](dsl-authentication.md) page for details on defining authentication policies.

**Error Handling**: If authentication fails during the connection attempt to the broker/server (e.g., invalid credentials, rejected token), the runtime should raise an `Authentication` (401) or `Authorization` (403) error. This prevents the publish/subscribe operation and halts the task unless the error is caught by a `Try` block.

### Data Flow
<include from="_common-task-data-flow.md" element-id="common-data-flow"/>
**Note**:
*   The `transformedInput` to the task is available for use within runtime expressions in the `with` properties (e.g., constructing `message.payload`, `subscription.filter`).
*   For *publish* operations (`with.message`), the `rawOutput` might indicate success/failure or be minimal.
*   For *subscribe* operations (`with.subscription`), the `rawOutput` typically contains the consumed messages, potentially processed by `subscription.foreach` (often as an array).
*   Standard `output.as` and `export.as` process this resulting `rawOutput`.

### Flow Control
<include from="_common-task-flow_control.md" element-id="common-flow-control"/>
**Note**: If the AsyncAPI operation fails (e.g., connection error, publish error, subscription timeout/error), a `Communication` error is typically raised, and the `then` directive is *not* followed (unless caught by `Try`). 