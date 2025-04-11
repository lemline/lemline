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
        operation: sendGreeting  
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
  - afterPublish:
      # ... 
```

### Example: Subscribing to Messages

```yaml
document:
  dsl: '1.0.0'    # Workflow DSL version
  namespace: test  # Namespace for the workflow
  name: asyncapi-subscribe-example  # Name of the workflow
  version: '0.1.0'  # Version of this workflow
do:
  - subscribeToChatRoom:  # Task name for the subscription
      call: asyncapi     # Use the AsyncAPI task type
      with:
        # Reference to the AsyncAPI specification document
        document:
          endpoint: https://chat.example.com/api/asyncapi.yaml
        
        # Operation ID for subscribing (must match one defined in the AsyncAPI doc)
        operation: receiveChatMessages
        
        # Specify protocol to select appropriate server
        protocol: ws  # WebSocket protocol
        
        subscription:
          # Optional: Filter messages based on payload content
          filter: '${ .roomId == $context.targetRoomId }'
          
          # Define consumption limits
          consume:
            amount: 10          # Max 10 messages
            for: { minutes: 5 } # Or max 5 minutes, whichever comes first
          
          # Process each consumed message
          foreach:
            item: msg  # Variable name for the current message
            do:
              # Log each order update
              - logUpdate:
                  call: logMessage
                  with:
                    message: "Received order update: ${ .msg.payload.orderId }"
              
              # Conditionally notify shipping if status is SHIPPED
              - checkStatus:
                  call: notifyShipping
                  if: "${ .msg.payload.status == 'SHIPPED' }"
            
            # Define the output of the foreach loop (and thus the task)
            output:
              as:
                processedCount: "${ count(.) }"          # Total messages processed
                lastOrderId: "${ .[-1]?.msg.payload.orderId }"  # Last order ID
  
  - afterSubscription:
      # ...
```

## Configuration Options

The configuration for an AsyncAPI call is provided within the `with` property of the `call: asyncapi` task.

### `with` (Object, Required)

*   **`document`** (Object, Required): Defines the location of the AsyncAPI specification document (JSON or YAML). Contains:
    *   `endpoint` (Object, Required): Specifies the location with `uri` (String | Object, Required) and optional `authentication` (String | Object).
*   **`operation`** (String, Required): The operation (publish or subscribe) to invoke, as defined within the AsyncAPI `document`.
*   **`server`** (Object, Optional): Configuration for connecting to a specific server defined in the AsyncAPI document. If omitted, the runtime selects a suitable server based on the operation and `protocol`. Contains:
    *   `name` (String, Required): The name of the server (must match a server name defined in the AsyncAPI document under the specified operation/channel).
    *   `variables` (Object, Optional): A key/value map to override [Server Variables](https://www.asyncapi.com/docs/reference/specification/v3.0.0#serverVariableObject) defined in the AsyncAPI document for the selected server.
*   **`protocol`** (String, Optional): The protocol to use, helping select the target server if `server` is not specified or if multiple servers support the operation. Supported values include: `amqp`, `amqp1`, `anypointmq`, `googlepubsub`, `http`, `ibmmq`, `jms`, `kafka`, `mercure`, `mqtt`, `mqtt5`, `nats`, `pulsar`, `redis`, `sns`, `solace`, `sqs`, `stomp`, `ws`.
*   **`message`** (Object, Conditionally Required): Defines the message to be published. Required if the `operation` represents a *publish* action. Contains details matching the [AsyncAPI Message Object](https://www.asyncapi.com/docs/reference/specification/v3.0.0#messageObject), such as:
    *   `payload` (Any, Optional): The main content/body of the message.
    *   `headers` (Object, Optional): Application-specific headers for the message.
    *   `correlationId` (String, Optional): ID used for message correlation.
    *   (Other properties like `contentType`, `name`, `title`, `summary`, `description`, `tags`, `externalDocs`, `bindings`, `examples`, `traits` may be supported depending on runtime capabilities).
*   **`subscription`** (Object, Conditionally Required): Defines how to subscribe to and consume messages. Required if the `operation` represents a *subscribe* action. Contains:
    *   `