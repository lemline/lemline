---
title: AsyncAPI Call
---
<!-- Examples are validated -->

# AsyncAPI Call Task (`call: asyncapi`)

> **Lemline runtime:** `call: asyncapi` is not executed by Lemline yet; use `emit` and `listen` tasks.


## Purpose

The AsyncAPI Call task enables workflows to interact with message brokers and event-driven services described by an [AsyncAPI](https://www.asyncapi.com/) specification document.

This allows workflows to publish messages to channels or subscribe to messages from channels defined in the AsyncAPI document.

## Usage Examples

### Example: Publishing a Message

<code-block lang="yaml" src="workflows/dsl-call-asyncapi/example-publishing-a-message.yaml"/>

### Example: Subscribing to Messages

<code-block lang="yaml" src="workflows/dsl-call-asyncapi/example-subscribing-to-messages.yaml"/>

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